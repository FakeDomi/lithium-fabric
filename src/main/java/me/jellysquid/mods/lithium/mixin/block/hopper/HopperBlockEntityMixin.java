package me.jellysquid.mods.lithium.mixin.block.hopper;

import me.jellysquid.mods.lithium.api.inventory.LithiumCooldownReceivingInventory;
import me.jellysquid.mods.lithium.api.inventory.LithiumInventory;
import me.jellysquid.mods.lithium.common.block.entity.SleepingBlockEntity;
import me.jellysquid.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeListener;
import me.jellysquid.mods.lithium.common.block.entity.inventory_change_tracking.InventoryChangeTracker;
import me.jellysquid.mods.lithium.common.block.entity.inventory_comparator_tracking.ComparatorTracker;
import me.jellysquid.mods.lithium.common.compat.fabric_transfer_api_v1.FabricTransferApiCompat;
import me.jellysquid.mods.lithium.common.entity.movement_tracker.SectionedEntityMovementListener;
import me.jellysquid.mods.lithium.common.entity.movement_tracker.SectionedInventoryEntityMovementTracker;
import me.jellysquid.mods.lithium.common.entity.movement_tracker.SectionedItemEntityMovementTracker;
import me.jellysquid.mods.lithium.common.hopper.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import static net.minecraft.block.entity.HopperBlockEntity.getInputItemEntities;


@Mixin(value = HopperBlockEntity.class, priority = 950)
public abstract class HopperBlockEntityMixin extends BlockEntity implements Hopper, UpdateReceiver, LithiumInventory, InventoryChangeListener, SectionedEntityMovementListener {

    public HopperBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Shadow
    protected abstract boolean isDisabled();

    @Shadow
    private int transferCooldown;

    @Unique
    private int itemPickupCooldown;

    @Shadow
    private long lastTickTime;

    @Shadow
    private static native boolean insert(World world, BlockPos pos, HopperBlockEntity blockEntity);

    @Shadow
    private static native boolean extract(Hopper hopper, Inventory inventory, int slot, Direction side);

    @Shadow
    private static native int[] getAvailableSlots(Inventory inventory, Direction side);

    private long myModCountAtLastInsert, myModCountAtLastExtract, myModCountAtLastItemCollect;

    private HopperCachingState.BlockInventory insertionMode = HopperCachingState.BlockInventory.UNKNOWN;
    private HopperCachingState.BlockInventory extractionMode = HopperCachingState.BlockInventory.UNKNOWN;

    //The currently used block inventories
    @Nullable
    private Inventory insertBlockInventory, extractBlockInventory;

    //The currently used inventories (optimized type, if not present, skip optimizations)
    @Nullable
    private LithiumInventory insertInventory, extractInventory;
    @Nullable //Null iff corresp. LithiumInventory field is null
    private LithiumStackList insertStackList, extractStackList;
    //Mod count used to avoid transfer attempts that are known to fail (no change since last attempt)
    private long insertStackListModCount, extractStackListModCount;

    private SectionedItemEntityMovementTracker<ItemEntity> collectItemEntityTracker;
    private boolean collectItemEntityTrackerWasEmpty;
    private Box collectItemEntityBox;
    private long collectItemEntityAttemptTime;

    private SectionedInventoryEntityMovementTracker<Inventory> extractInventoryEntityTracker;
    private Box extractInventoryEntityBox;
    private long extractInventoryEntityFailedSearchTime;

    private SectionedInventoryEntityMovementTracker<Inventory> insertInventoryEntityTracker;
    private Box insertInventoryEntityBox;
    private long insertInventoryEntityFailedSearchTime;

    private boolean shouldCheckSleep;

    @Redirect(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;getInputInventory(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/inventory/Inventory;"))
    private static Inventory getExtractInventory(World world, Hopper hopper, BlockPos extractBlockPos, BlockState extractBlockState) {
        if (!(hopper instanceof HopperBlockEntityMixin hopperBlockEntity)) {
            return getInputInventory(world, hopper, extractBlockPos, extractBlockState); //Hopper Minecarts do not cache Inventories
        }

        Inventory blockInventory = hopperBlockEntity.getExtractBlockInventory(world, extractBlockPos, extractBlockState);
        if (blockInventory != null) {
            return blockInventory;
        }

        if (hopperBlockEntity.extractInventoryEntityTracker == null) {
            hopperBlockEntity.initExtractInventoryTracker(world);
        }
        if (hopperBlockEntity.extractInventoryEntityTracker.isUnchangedSince(hopperBlockEntity.extractInventoryEntityFailedSearchTime)) {
            hopperBlockEntity.extractInventoryEntityFailedSearchTime = hopperBlockEntity.lastTickTime;
            return null;
        }
        hopperBlockEntity.extractInventoryEntityFailedSearchTime = Long.MIN_VALUE;
        hopperBlockEntity.shouldCheckSleep = false;

        List<Inventory> inventoryEntities = hopperBlockEntity.extractInventoryEntityTracker.getEntities(hopperBlockEntity.extractInventoryEntityBox);
        if (inventoryEntities.isEmpty()) {
            hopperBlockEntity.extractInventoryEntityFailedSearchTime = hopperBlockEntity.lastTickTime;
            //only set unchanged when no entity present. this allows shortcutting this case
            //shortcutting the entity present case requires checking its change counter
            return null;
        }
        Inventory inventory = inventoryEntities.get(world.random.nextInt(inventoryEntities.size()));
        if (inventory instanceof LithiumInventory optimizedInventory) {
            LithiumStackList extractInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
            if (inventory != hopperBlockEntity.extractInventory || hopperBlockEntity.extractStackList != extractInventoryStackList) {
                //not caching the inventory (NO_BLOCK_INVENTORY prevents it)
                //make change counting on the entity inventory possible, without caching it as block inventory
                hopperBlockEntity.cacheExtractLithiumInventory(optimizedInventory);
            }
        }
        return inventory;
    }

    /**
     * Effectively overwrites {@link HopperBlockEntity#insert(World, BlockPos, BlockState, Inventory)} (only usage redirect)
     * [VanillaCopy] general hopper insert logic, modified for optimizations
     *
     * @reason Adding the inventory caching into the static method using mixins seems to be unfeasible without temporarily storing state in static fields.
     */
    @SuppressWarnings("JavadocReference")
    @Inject(
            cancellable = true,
            method = "insert(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/HopperBlockEntity;)Z",
            at = @At(
                    value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/block/entity/HopperBlockEntity;isInventoryFull(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/util/math/Direction;)Z"
            ), locals = LocalCapture.CAPTURE_FAILHARD
    )
    private static void lithiumInsert(World world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir, Inventory insertInventory, Direction direction) {
        if (insertInventory == null || !(blockEntity instanceof HopperBlockEntity) || blockEntity instanceof SidedInventory) {
            //call the vanilla code to allow other mods inject features
            //e.g. carpet mod allows hoppers to insert items into wool blocks
            return;
        }
        HopperBlockEntityMixin hopperBlockEntity = (HopperBlockEntityMixin) (Object) blockEntity;

        LithiumStackList hopperStackList = InventoryHelper.getLithiumStackList(hopperBlockEntity);
        if (hopperBlockEntity.insertInventory == insertInventory && hopperStackList.getModCount() == hopperBlockEntity.myModCountAtLastInsert) {
            if (hopperBlockEntity.insertStackList != null && hopperBlockEntity.insertStackList.getModCount() == hopperBlockEntity.insertStackListModCount) {
//                ComparatorUpdatePattern.NO_UPDATE.apply(hopperBlockEntity, hopperStackList); //commented because it's a noop, Hoppers do not send useless comparator updates
                cir.setReturnValue(false);
                return;
            }
        }

        boolean insertInventoryWasEmptyHopperNotDisabled = insertInventory instanceof HopperBlockEntityMixin &&
                !((HopperBlockEntityMixin) insertInventory).isDisabled() && hopperBlockEntity.insertStackList != null &&
                hopperBlockEntity.insertStackList.getOccupiedSlots() == 0;

        boolean insertInventoryHandlesModdedCooldown =
                ((LithiumCooldownReceivingInventory) insertInventory).canReceiveTransferCooldown() &&
                        hopperBlockEntity.insertStackList != null ?
                        hopperBlockEntity.insertStackList.getOccupiedSlots() == 0 :
                        insertInventory.isEmpty();


        //noinspection ConstantConditions
        if (!(hopperBlockEntity.insertInventory == insertInventory && hopperBlockEntity.insertStackList.getFullSlots() == hopperBlockEntity.insertStackList.size())) {
            Direction fromDirection = hopperBlockEntity.facing.getOpposite();
            int size = hopperStackList.size();
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < size; ++i) {
                ItemStack transferStack = hopperStackList.get(i);
                if (!transferStack.isEmpty()) {
                    boolean transferSuccess = HopperHelper.tryMoveSingleItem(insertInventory, transferStack, fromDirection);
                    if (transferSuccess) {
                        if (insertInventoryWasEmptyHopperNotDisabled) {
                            HopperBlockEntityMixin receivingHopper = (HopperBlockEntityMixin) insertInventory;
                            int k = 8;
                            if (receivingHopper.lastTickTime >= hopperBlockEntity.lastTickTime) {
                                k = 7;
                            }
                            receivingHopper.setTransferCooldown(k);
                        }
                        if (insertInventoryHandlesModdedCooldown) {
                            ((LithiumCooldownReceivingInventory) insertInventory).setTransferCooldown(hopperBlockEntity.lastTickTime);
                        }
                        insertInventory.markDirty();
                        cir.setReturnValue(true);
                        return;
                    }
                }
            }
        }
        hopperBlockEntity.myModCountAtLastInsert = hopperStackList.getModCount();
        if (hopperBlockEntity.insertStackList != null) {
            hopperBlockEntity.insertStackListModCount = hopperBlockEntity.insertStackList.getModCount();
        }
        cir.setReturnValue(false);
    }

    /**
     * Inject to replace the extract method with an optimized but equivalent replacement.
     * Uses the vanilla method as fallback for non-optimized Inventories.
     *
     * @param to   Hopper or Hopper Minecart that is extracting
     * @param from Inventory the hopper is extracting from
     */
    @Inject(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At(value = "FIELD", target = "Lnet/minecraft/util/math/Direction;DOWN:Lnet/minecraft/util/math/Direction;", shift = At.Shift.AFTER), cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    private static void lithiumExtract(World world, Hopper to, CallbackInfoReturnable<Boolean> cir, BlockPos blockPos, BlockState blockState, Inventory from) {
        if (!(to instanceof HopperBlockEntityMixin hopperBlockEntity)) {
            return; //optimizations not implemented for hopper minecarts
        }
        if (from != hopperBlockEntity.extractInventory || hopperBlockEntity.extractStackList == null) {
            return; //from inventory is not an optimized inventory, vanilla fallback
        }

        LithiumStackList hopperStackList = InventoryHelper.getLithiumStackList(hopperBlockEntity);
        LithiumStackList fromStackList = hopperBlockEntity.extractStackList;

        if (hopperStackList.getModCount() == hopperBlockEntity.myModCountAtLastExtract) {
            if (fromStackList.getModCount() == hopperBlockEntity.extractStackListModCount) {
                if (!(from instanceof ComparatorTracker comparatorTracker) || comparatorTracker.lithium$hasAnyComparatorNearby()) {
                    //noinspection CollectionAddedToSelf
                    fromStackList.runComparatorUpdatePatternOnFailedExtract(fromStackList, from);
                }
                cir.setReturnValue(false);
                return;
            }
        }

        int[] availableSlots = from instanceof SidedInventory ? ((SidedInventory) from).getAvailableSlots(Direction.DOWN) : null;
        int fromSize = availableSlots != null ? availableSlots.length : from.size();
        for (int i = 0; i < fromSize; i++) {
            int fromSlot = availableSlots != null ? availableSlots[i] : i;
            ItemStack itemStack = fromStackList.get(fromSlot);
            if (!itemStack.isEmpty() && canExtract(to , from, itemStack, fromSlot, Direction.DOWN)) {
                //calling removeStack is necessary due to its side effects (markDirty in LootableContainerBlockEntity)
                ItemStack takenItem = from.removeStack(fromSlot, 1);
                assert !takenItem.isEmpty();
                boolean transferSuccess = HopperHelper.tryMoveSingleItem(to, takenItem, null);
                if (transferSuccess) {
                    to.markDirty();
                    from.markDirty();
                    cir.setReturnValue(true);
                    return;
                }
                //put the item back similar to vanilla
                ItemStack restoredStack = fromStackList.get(fromSlot);
                if (restoredStack.isEmpty()) {
                    restoredStack = takenItem;
                } else {
                    restoredStack.increment(1);
                }
                //calling setStack is necessary due to its side effects (markDirty in LootableContainerBlockEntity)
                from.setStack(fromSlot, restoredStack);
            }
        }
        hopperBlockEntity.myModCountAtLastExtract = hopperStackList.getModCount();
        if (fromStackList != null) {
            hopperBlockEntity.extractStackListModCount = fromStackList.getModCount();
        }
        cir.setReturnValue(false);
    }

    @Redirect(
            method = "insertAndExtract(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/entity/HopperBlockEntity;isFull()Z"
            )
    )
    private static boolean lithiumHopperIsFull(HopperBlockEntity hopperBlockEntity) {
        //noinspection ConstantConditions
        LithiumStackList lithiumStackList = InventoryHelper.getLithiumStackList((HopperBlockEntityMixin) (Object) hopperBlockEntity);
        return lithiumStackList.getFullSlots() == lithiumStackList.size();
    }

    @Redirect(
            method = "insertAndExtract(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/entity/HopperBlockEntity;isEmpty()Z"
            )
    )
    private static boolean lithiumHopperIsEmpty(HopperBlockEntity hopperBlockEntity) {
        //noinspection ConstantConditions
        LithiumStackList lithiumStackList = InventoryHelper.getLithiumStackList((HopperBlockEntityMixin) (Object) hopperBlockEntity);
        return lithiumStackList.getOccupiedSlots() == 0;
    }

    @Shadow
    protected abstract void setTransferCooldown(int cooldown);

    @Shadow
    protected abstract boolean needsCooldown();

    @Shadow
    private static native boolean canExtract(Inventory hopperInventory, Inventory fromInventory, ItemStack stack, int slot, Direction facing);

    @Shadow
    private Direction facing;

    @Shadow
    @Nullable
    private static native Inventory getBlockInventoryAt(World world, BlockPos pos, BlockState state);

    @Shadow
    @Nullable
    protected static native Inventory getInputInventory(World world, Hopper hopper, BlockPos pos, BlockState state);

    @Override
    public void lithium$invalidateCacheOnNeighborUpdate(boolean fromAbove) {
        //Clear the block inventory cache (composter inventories and no inventory present) on block update / observer update
        if (fromAbove) {
            if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY || this.extractionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
                this.invalidateBlockExtractionData();
            }
        } else {
            if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY || this.insertionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
                this.invalidateBlockInsertionData();
            }
        }
    }

    @Override
    public void lithium$invalidateCacheOnNeighborUpdate(Direction fromDirection) {
        boolean fromAbove = fromDirection == Direction.UP;
        if (fromAbove || this.getCachedState().get(HopperBlock.FACING) == fromDirection) {
            this.lithium$invalidateCacheOnNeighborUpdate(fromAbove);
        }
    }

    @Redirect(method = "insert(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/HopperBlockEntity;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;getOutputInventory(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/HopperBlockEntity;)Lnet/minecraft/inventory/Inventory;"))
    private static Inventory getLithiumOutputInventory(World world, BlockPos pos, HopperBlockEntity blockEntity) {
        HopperBlockEntityMixin hopperBlockEntity = (HopperBlockEntityMixin) (Object) blockEntity;
        return hopperBlockEntity.getInsertInventory(world);
    }

    @Redirect(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;getInputItemEntities(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Ljava/util/List;"))
    private static List<ItemEntity> lithiumGetInputItemEntities(World world, Hopper hopper) {
        if (!(hopper instanceof HopperBlockEntityMixin hopperBlockEntity)) {
            return getInputItemEntities(world, hopper); //optimizations not implemented for hopper minecarts
        }

        if (hopperBlockEntity.collectItemEntityTracker == null) {
            hopperBlockEntity.initCollectItemEntityTracker();
        }
        long modCount = InventoryHelper.getLithiumStackList(hopperBlockEntity).getModCount();
        if ((hopperBlockEntity.collectItemEntityTrackerWasEmpty || hopperBlockEntity.myModCountAtLastItemCollect == modCount) &&
                hopperBlockEntity.collectItemEntityTracker.isUnchangedSince(hopperBlockEntity.collectItemEntityAttemptTime)) {
            hopperBlockEntity.collectItemEntityAttemptTime = hopperBlockEntity.lastTickTime;
            return Collections.emptyList();
        }
        hopperBlockEntity.myModCountAtLastItemCollect = modCount;
        hopperBlockEntity.shouldCheckSleep = false;

        List<ItemEntity> itemEntities = hopperBlockEntity.collectItemEntityTracker.getEntities(hopperBlockEntity.collectItemEntityBox);
        hopperBlockEntity.collectItemEntityAttemptTime = hopperBlockEntity.lastTickTime;
        hopperBlockEntity.collectItemEntityTrackerWasEmpty = itemEntities.isEmpty();
        //set unchanged so that if this extract fails and there is no other change to hoppers or items, extracting
        // items can be skipped.
        return itemEntities;
    }

    /**
     * Makes this hopper remember the given inventory.
     *
     * @param insertInventory Block inventory / Blockentity inventory to be remembered
     */
    private void cacheInsertBlockInventory(Inventory insertInventory) {
        assert !(insertInventory instanceof Entity);
        if (insertInventory instanceof LithiumInventory optimizedInventory) {
            this.cacheInsertLithiumInventory(optimizedInventory);
        } else {
            this.insertInventory = null;
            this.insertStackList = null;
            this.insertStackListModCount = 0;
        }

        if (insertInventory instanceof BlockEntity || insertInventory instanceof DoubleInventory) {
            this.insertBlockInventory = insertInventory;
            if (insertInventory instanceof InventoryChangeTracker) {
                this.insertionMode = HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY;
                ((InventoryChangeTracker) insertInventory).listenForMajorInventoryChanges(this);
            } else {
                this.insertionMode = HopperCachingState.BlockInventory.BLOCK_ENTITY;
            }
        } else {
            if (insertInventory == null) {
                this.insertBlockInventory = null;
                this.insertionMode = HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY;
            } else {
                this.insertBlockInventory = insertInventory;
                this.insertionMode = insertInventory instanceof BlockStateOnlyInventory ? HopperCachingState.BlockInventory.BLOCK_STATE : HopperCachingState.BlockInventory.UNKNOWN;
            }
        }
    }

    private void cacheInsertLithiumInventory(LithiumInventory optimizedInventory) {
        LithiumStackList insertInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
        this.insertInventory = optimizedInventory;
        this.insertStackList = insertInventoryStackList;
        this.insertStackListModCount = insertInventoryStackList.getModCount() - 1;
    }

    private void cacheExtractLithiumInventory(LithiumInventory optimizedInventory) {
        LithiumStackList extractInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
        this.extractInventory = optimizedInventory;
        this.extractStackList = extractInventoryStackList;
        this.extractStackListModCount = extractInventoryStackList.getModCount() - 1;
    }

    /**
     * @author 2No2Name
     */
    @Unique
    private static boolean isInventoryEmpty(Inventory inv, Direction side) {
        int[] availableSlots = inv instanceof SidedInventory ? ((SidedInventory) inv).getAvailableSlots(side) : null;
        int fromSize = availableSlots != null ? availableSlots.length : inv.size();
        for (int i = 0; i < fromSize; i++) {
            int slot = availableSlots != null ? availableSlots[i] : i;
            if (!inv.getStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Makes this hopper remember the given inventory.
     *
     * @param extractInventory Block inventory / Blockentity inventory to be remembered
     */
    private void cacheExtractBlockInventory(Inventory extractInventory) {
        assert !(extractInventory instanceof Entity);
        if (extractInventory instanceof LithiumInventory optimizedInventory) {
            this.cacheExtractLithiumInventory(optimizedInventory);
        } else {
            this.extractInventory = null;
            this.extractStackList = null;
            this.extractStackListModCount = 0;
        }

        if (extractInventory instanceof BlockEntity || extractInventory instanceof DoubleInventory) {
            this.extractBlockInventory = extractInventory;
            if (extractInventory instanceof InventoryChangeTracker) {
                this.extractionMode = HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY;
                ((InventoryChangeTracker) extractInventory).listenForMajorInventoryChanges(this);
            } else {
                this.extractionMode = HopperCachingState.BlockInventory.BLOCK_ENTITY;
            }
        } else {
            if (extractInventory == null) {
                this.extractBlockInventory = null;
                this.extractionMode = HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY;
            } else {
                this.extractBlockInventory = extractInventory;
                this.extractionMode = extractInventory instanceof BlockStateOnlyInventory ? HopperCachingState.BlockInventory.BLOCK_STATE : HopperCachingState.BlockInventory.UNKNOWN;
            }
        }
    }

    public Inventory getExtractBlockInventory(World world, BlockPos extractBlockPos, BlockState extractBlockState) {
        Inventory blockInventory = this.extractBlockInventory;
        if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
            return null;
        } else if (this.extractionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
            return blockInventory;
        } else if (this.extractionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            return blockInventory;
        } else if (this.extractionMode == HopperCachingState.BlockInventory.BLOCK_ENTITY) {
            BlockEntity blockEntity = (BlockEntity) Objects.requireNonNull(blockInventory);
            //Movable Block Entity compatibility - position comparison
            BlockPos pos = blockEntity.getPos();
            if (!(blockEntity).isRemoved() && pos.equals(extractBlockPos)) {
                LithiumInventory optimizedInventory;
                if ((optimizedInventory = this.extractInventory) != null) {
                    LithiumStackList insertInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
                    //This check is necessary as sometimes the stacklist is silently replaced (e.g. command making furnace read inventory from nbt)
                    if (insertInventoryStackList == this.extractStackList) {
                        return optimizedInventory;
                    } else {
                        this.invalidateBlockExtractionData();
                    }
                } else {
                    return blockInventory;
                }
            }
        }

        //No Cached Inventory: Get like vanilla and cache
        blockInventory = getBlockInventoryAt(world, extractBlockPos, extractBlockState);
        blockInventory = HopperHelper.replaceDoubleInventory(blockInventory);
        this.cacheExtractBlockInventory(blockInventory);
        return blockInventory;
    }

    public Inventory getInsertBlockInventory(World world) {
        Inventory blockInventory = this.insertBlockInventory;
        if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
            return null;
        } else if (this.insertionMode == HopperCachingState.BlockInventory.BLOCK_STATE) {
            return blockInventory;
        } else if (this.insertionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            return blockInventory;
        } else if (this.insertionMode == HopperCachingState.BlockInventory.BLOCK_ENTITY) {
            BlockEntity blockEntity = (BlockEntity) Objects.requireNonNull(blockInventory);
            //Movable Block Entity compatibility - position comparison
            BlockPos pos = blockEntity.getPos();
            Direction direction = this.facing;
            BlockPos transferPos = this.getPos().offset(direction);
            if (!(blockEntity).isRemoved() &&
                    pos.equals(transferPos)) {
                LithiumInventory optimizedInventory;
                if ((optimizedInventory = this.insertInventory) != null) {
                    LithiumStackList insertInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
                    //This check is necessary as sometimes the stacklist is silently replaced (e.g. command making furnace read inventory from nbt)
                    if (insertInventoryStackList == this.insertStackList) {
                        return optimizedInventory;
                    } else {
                        this.invalidateBlockInsertionData();
                    }
                } else {
                    return blockInventory;
                }
            }
        }

        //No Cached Inventory: Get like vanilla and cache
        Direction direction = this.facing;
        BlockPos insertBlockPos = this.getPos().offset(direction);
        BlockState blockState = world.getBlockState(insertBlockPos);
        blockInventory = getBlockInventoryAt(world, insertBlockPos, blockState);
        blockInventory = HopperHelper.replaceDoubleInventory(blockInventory);
        this.cacheInsertBlockInventory(blockInventory);
        return blockInventory;
    }


    public Inventory getInsertInventory(World world) {
        Inventory blockInventory = this.getInsertBlockInventory(world);
        if (blockInventory != null) {
            return blockInventory;
        }

        if (this.insertInventoryEntityTracker == null) {
            this.initInsertInventoryTracker(world);
        }
        if (this.insertInventoryEntityTracker.isUnchangedSince(this.insertInventoryEntityFailedSearchTime)) {
            this.insertInventoryEntityFailedSearchTime = this.lastTickTime;
            return null;
        }
        this.insertInventoryEntityFailedSearchTime = Long.MIN_VALUE;
        this.shouldCheckSleep = false;

        List<Inventory> inventoryEntities = this.insertInventoryEntityTracker.getEntities(this.insertInventoryEntityBox);
        if (inventoryEntities.isEmpty()) {
            this.insertInventoryEntityFailedSearchTime = this.lastTickTime;
            //Remember failed entity search timestamp. This allows shortcutting if no entity movement happens.
            return null;
        }
        Inventory inventory = inventoryEntities.get(world.random.nextInt(inventoryEntities.size()));
        if (inventory instanceof LithiumInventory optimizedInventory) {
            LithiumStackList insertInventoryStackList = InventoryHelper.getLithiumStackList(optimizedInventory);
            if (inventory != this.insertInventory || this.insertStackList != insertInventoryStackList) {
                this.cacheInsertLithiumInventory(optimizedInventory);
            }
        }

        return inventory;
    }

    //Entity tracker initialization:

    private void initCollectItemEntityTracker() {
        assert this.world instanceof ServerWorld;
        Box inputBox = this.getInputAreaShape().offset(this.pos.getX(), this.pos.getY(), this.pos.getZ());
        this.collectItemEntityBox = inputBox;
        this.collectItemEntityTracker =
                SectionedItemEntityMovementTracker.registerAt(
                        (ServerWorld) this.world,
                        inputBox,
                        ItemEntity.class
                );
        this.collectItemEntityAttemptTime = Long.MIN_VALUE;
    }

    private void initExtractInventoryTracker(World world) {
        assert world instanceof ServerWorld;
        BlockPos pos = this.pos.offset(Direction.UP);
        this.extractInventoryEntityBox = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        this.extractInventoryEntityTracker =
                SectionedInventoryEntityMovementTracker.registerAt(
                        (ServerWorld) this.world,
                        this.extractInventoryEntityBox,
                        Inventory.class
                );
        this.extractInventoryEntityFailedSearchTime = Long.MIN_VALUE;
    }

    private void initInsertInventoryTracker(World world) {
        assert world instanceof ServerWorld;
        Direction direction = this.facing;
        BlockPos pos = this.pos.offset(direction);
        this.insertInventoryEntityBox = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        this.insertInventoryEntityTracker =
                SectionedInventoryEntityMovementTracker.registerAt(
                        (ServerWorld) this.world,
                        this.insertInventoryEntityBox,
                        Inventory.class
                );
        this.insertInventoryEntityFailedSearchTime = Long.MIN_VALUE;
    }

    //Cached data invalidation:

    @Inject(method = "setCachedState(Lnet/minecraft/block/BlockState;)V", at = @At("HEAD"))
    private void invalidateOnSetCachedState(BlockState state, CallbackInfo ci) {
        if (this.world != null && !this.world.isClient() && state.get(HopperBlock.FACING) != this.getCachedState().get(HopperBlock.FACING)) {
            this.invalidateCachedData();
        }
    }

    private void invalidateCachedData() {
        this.shouldCheckSleep = false;
        this.invalidateInsertionData();
        this.invalidateExtractionData();
    }

    private void invalidateInsertionData() {
        if (this.world instanceof ServerWorld serverWorld) {
            if (this.insertInventoryEntityTracker != null) {
                this.insertInventoryEntityTracker.unRegister(serverWorld);
                this.insertInventoryEntityTracker = null;
                this.insertInventoryEntityBox = null;
                this.insertInventoryEntityFailedSearchTime = 0L;
            }
        }

        if (this.insertionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            assert this.insertBlockInventory != null;
            ((InventoryChangeTracker) this.insertBlockInventory).stopListenForMajorInventoryChanges(this);
        }
        this.invalidateBlockInsertionData();
    }

    private void invalidateBlockInsertionData() {
        this.insertionMode = HopperCachingState.BlockInventory.UNKNOWN;
        this.insertBlockInventory = null;
        this.insertInventory = null;
        this.insertStackList = null;
        this.insertStackListModCount = 0;

        if (this instanceof SleepingBlockEntity sleepingBlockEntity) {
            sleepingBlockEntity.wakeUpNow();
        }
    }

    private void invalidateExtractionData() {
        if (this.world instanceof ServerWorld serverWorld) {
            if (this.extractInventoryEntityTracker != null) {
                this.extractInventoryEntityTracker.unRegister(serverWorld);
                this.extractInventoryEntityTracker = null;
                this.extractInventoryEntityBox = null;
                this.extractInventoryEntityFailedSearchTime = 0L;
            }
            if (this.collectItemEntityTracker != null) {
                this.collectItemEntityTracker.unRegister(serverWorld);
                this.collectItemEntityTracker = null;
                this.collectItemEntityBox = null;
                this.collectItemEntityTrackerWasEmpty = false;
            }
        }
        if (this.extractionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
            assert this.extractBlockInventory != null;
            ((InventoryChangeTracker) this.extractBlockInventory).stopListenForMajorInventoryChanges(this);
        }
        this.invalidateBlockExtractionData();
    }

    private void invalidateBlockExtractionData() {
        this.extractionMode = HopperCachingState.BlockInventory.UNKNOWN;
        this.extractBlockInventory = null;
        this.extractInventory = null;
        this.extractStackList = null;
        this.extractStackListModCount = 0;

        if (this instanceof SleepingBlockEntity sleepingBlockEntity) {
            sleepingBlockEntity.wakeUpNow();
        }
    }

    /**
     * @author Domi
     * @reason It's easier this way
     */
    @Overwrite
    public static void serverTick(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity) {
        if (world.isClient()) {
            return;
        }

        //noinspection ConstantConditions
        HopperBlockEntityMixin hopper = (HopperBlockEntityMixin) (Object) blockEntity;
        boolean enabled = state.get(HopperBlock.ENABLED);
        int transferCooldown = hopper.transferCooldown - 1;

        hopper.lastTickTime = world.getTime();

        Inventory invAbove = null;
        boolean cachedInv = false;

        boolean hasDoneWork = false;
        boolean shouldCheckSleepingConditions = false;

        if (transferCooldown <= 0) {
            transferCooldown = 0;

            if (enabled) {
                if (!lithiumHopperIsEmpty(blockEntity)) {
                    hasDoneWork = insert(world, pos, blockEntity);
                }

                if (!lithiumHopperIsFull(blockEntity)) {
                    BlockPos posAbove = pos.up();
                    invAbove = getExtractInventory(world, blockEntity, posAbove, world.getBlockState(posAbove));
                    cachedInv = true;

                    if (invAbove != null) {
                        CallbackInfoReturnable<Boolean> cir = new CallbackInfoReturnable<>("extract", true);
                        lithiumExtract(world, blockEntity, cir, null, null, invAbove);

                        if (cir.isCancelled()) {
                            hasDoneWork |= cir.getReturnValue();
                        } else {
                            if (!isInventoryEmpty(invAbove, Direction.DOWN)) {
                                // vanilla inv
                                for (int slot : getAvailableSlots(invAbove, Direction.DOWN)) {
                                    if (extract(blockEntity, invAbove, slot, Direction.DOWN)) {
                                        hasDoneWork = true;
                                    }
                                }
                            }
                        }
                    }
                }

                if (hasDoneWork) {
                    transferCooldown = 8;
                }
            }

            shouldCheckSleepingConditions = true;
        }

        hopper.transferCooldown = transferCooldown;

        int itemPickupCooldown = hopper.itemPickupCooldown - 1;

        if (itemPickupCooldown <= 0) {
            itemPickupCooldown = 0;

            if (enabled) {
                BlockPos posAbove = pos.up();
                boolean canPickUp = cachedInv ? (invAbove == null && !lithiumHopperIsFull(blockEntity)) : (!lithiumHopperIsFull(blockEntity) && getExtractInventory(world, blockEntity, posAbove, world.getBlockState(posAbove)) == null);

                if (canPickUp && tryPickupItems(world, blockEntity)) {
                    hasDoneWork = true;
                    itemPickupCooldown = 8;
                }
            }

            shouldCheckSleepingConditions = true;
        }

        hopper.itemPickupCooldown = itemPickupCooldown;

        if (hasDoneWork) {
            markDirty(world, pos, state);
        }

        if (shouldCheckSleepingConditions) {
            hopper.checkSleepingConditions();
        }
    }

    @Unique
    private static boolean tryPickupItems(World world, Hopper hopper) {
        for (ItemEntity item : lithiumGetInputItemEntities(world, hopper)) {
            if (HopperBlockEntity.extract(hopper, item)) {
                return true;
            }
        }

        return false;
    }

    private void checkSleepingConditions() {
        if (this.needsCooldown()) {
            return;
        }
        if (this instanceof SleepingBlockEntity thisSleepingBlockEntity) {
            if (thisSleepingBlockEntity.isSleeping()) {
                return;
            }
            if (!this.shouldCheckSleep) {
                this.shouldCheckSleep = true;
                return;
            }
            if (this instanceof InventoryChangeTracker thisTracker) {
                boolean listenToExtractTracker = false;
                boolean listenToInsertTracker = false;
                boolean listenToExtractEntities = false;
                boolean listenToInsertEntities = false;

                LithiumStackList thisStackList = InventoryHelper.getLithiumStackList(this);

                if (this.extractionMode != HopperCachingState.BlockInventory.BLOCK_STATE && thisStackList.getFullSlots() != thisStackList.size()) {
                    if (this.extractionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
                        Inventory blockInventory = this.extractBlockInventory;
                        if (this.extractStackList != null &&
                                blockInventory instanceof InventoryChangeTracker) {
                            if (!this.extractStackList.maybeSendsComparatorUpdatesOnFailedExtract() || (blockInventory instanceof ComparatorTracker comparatorTracker && !comparatorTracker.lithium$hasAnyComparatorNearby())) {
                                listenToExtractTracker = true;
                            } else {
                                return;
                            }
                        } else {
                            return;
                        }
                    } else if (this.extractionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
                        if (FabricTransferApiCompat.FABRIC_TRANSFER_API_V_1_PRESENT && FabricTransferApiCompat.canHopperInteractWithApiInventory((HopperBlockEntity) (Object) this, this.getCachedState(), true)) {
                            return;
                        }
                        listenToExtractEntities = true;
                    } else {
                        return;
                    }
                }
                if (this.insertionMode != HopperCachingState.BlockInventory.BLOCK_STATE && 0 < thisStackList.getOccupiedSlots()) {
                    if (this.insertionMode == HopperCachingState.BlockInventory.REMOVAL_TRACKING_BLOCK_ENTITY) {
                        Inventory blockInventory = this.insertBlockInventory;
                        if (this.insertStackList != null && blockInventory instanceof InventoryChangeTracker) {
                            listenToInsertTracker = true;
                        } else {
                            return;
                        }
                    } else if (this.insertionMode == HopperCachingState.BlockInventory.NO_BLOCK_INVENTORY) {
                        if (FabricTransferApiCompat.FABRIC_TRANSFER_API_V_1_PRESENT && FabricTransferApiCompat.canHopperInteractWithApiInventory((HopperBlockEntity) (Object) this, this.getCachedState(), false)) {
                            return;
                        }
                        listenToInsertEntities = true;
                    } else {
                        return;
                    }
                }

                if (listenToExtractTracker) {
                    ((InventoryChangeTracker) this.extractBlockInventory).listenForContentChangesOnce(this.extractStackList, this);
                }
                if (listenToInsertTracker) {
                    ((InventoryChangeTracker) this.insertBlockInventory).listenForContentChangesOnce(this.insertStackList, this);
                }
                if (listenToInsertEntities) {
                    if (this.insertInventoryEntityTracker == null) {
                        this.initInsertInventoryTracker(this.world);
                    }
                    this.insertInventoryEntityTracker.listenToEntityMovementOnce(this);
                }
                if (listenToExtractEntities) {
                    if (this.extractInventoryEntityTracker == null) {
                        this.initExtractInventoryTracker(this.world);
                    }
                    this.extractInventoryEntityTracker.listenToEntityMovementOnce(this);
                    if (this.collectItemEntityTracker == null) {
                        this.initCollectItemEntityTracker();
                    }
                    this.collectItemEntityTracker.listenToEntityMovementOnce(this);
                }
                thisTracker.listenForContentChangesOnce(thisStackList, this);
                thisSleepingBlockEntity.lithium$startSleeping();
            }
        }
    }

    @Override
    public void lithium$handleInventoryContentModified(Inventory inventory) {
        if (this instanceof SleepingBlockEntity sleepingBlockEntity) {
            sleepingBlockEntity.wakeUpNow();
        }
    }

    @Override
    public void lithium$handleInventoryRemoved(Inventory inventory) {
        if (this instanceof SleepingBlockEntity sleepingBlockEntity) {
            sleepingBlockEntity.wakeUpNow();
        }
        if (inventory == this.insertBlockInventory) {
            this.invalidateBlockInsertionData();
        }
        if (inventory == this.extractBlockInventory) {
            this.invalidateBlockExtractionData();
        }
        if (inventory == this) {
            this.invalidateCachedData();
        }
    }

    @Override
    public boolean lithium$handleComparatorAdded(Inventory inventory) {
        if (inventory == this.extractBlockInventory && this instanceof SleepingBlockEntity sleepingBlockEntity) {
            sleepingBlockEntity.wakeUpNow();
            return true;
        }
        return false;
    }

    @Override
    public void lithium$handleEntityMovement(Class<?> category) {
        if (this instanceof SleepingBlockEntity sleepingBlockEntity) {
            sleepingBlockEntity.wakeUpNow();
        }
    }
}
