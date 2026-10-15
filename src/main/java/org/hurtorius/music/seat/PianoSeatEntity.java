package org.hurtorius.music.seat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.PianoLayout;

/**
 * Invisible seat on the piano bench. The rider's position is the seat entity's position, which
 * {@link org.hurtorius.music.piano.GrandPianoBlockEntity} puts on the bench top. The seat removes
 * itself when it is empty or the piano is gone; it is saved only as the rider's vehicle. The anchor
 * is synced, so clients know which piano a seated player plays (for their hands and camera).
 */
public class PianoSeatEntity extends Entity {
    /** How far a seated performer may turn their head away from the keyboard, in degrees. */
    public static final float TURN_LIMIT = 75f;
    private static final EntityDataAccessor<BlockPos> ANCHOR = SynchedEntityData.defineId(PianoSeatEntity.class, EntityDataSerializers.BLOCK_POS);

    public PianoSeatEntity(EntityType<? extends PianoSeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public void setAnchor(BlockPos pos) {
        entityData.set(ANCHOR, pos.immutable());
    }

    /** Controller block of the piano this seat belongs to (on both sides). */
    public BlockPos anchor() {
        return entityData.get(ANCHOR);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ANCHOR, BlockPos.ZERO);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        setAnchor(input.read("Anchor", BlockPos.CODEC).orElse(BlockPos.ZERO));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.store("Anchor", BlockPos.CODEC, anchor());
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return Vec3.ZERO;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        super.positionRider(passenger, move);
        clampRotation(passenger);
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        clampRotation(passenger);
    }

    private void clampRotation(Entity passenger) {
        passenger.setYBodyRot(getYRot());
        float delta = Mth.wrapDegrees(passenger.getYRot() - getYRot());
        float clamped = Mth.clamp(delta, -TURN_LIMIT, TURN_LIMIT);
        passenger.yRotO += clamped - delta;
        passenger.setYRot(passenger.getYRot() + clamped - delta);
        passenger.setYHeadRot(passenger.getYRot());
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos anchor = anchor();
        Direction facing = level().getBlockState(anchor).getBlock() instanceof GrandPianoBlock
            ? level().getBlockState(anchor).getValue(GrandPianoBlock.FACING) : Direction.fromYRot(getYRot()).getOpposite();
        for (BlockPos candidate : PianoLayout.standingSpots(anchor, facing)) {
            Vec3 safe = DismountHelper.findSafeDismountLocation(passenger.getType(), level(), candidate, true);
            if (safe != null) return safe;
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (!isVehicle() || !(level().getBlockState(anchor()).getBlock() instanceof GrandPianoBlock)) {
            ejectPassengers();
            discard();
        }
    }
}
