/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.behavior.IElytraBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.elytra.NetherPathfinderContext;
import baritone.behavior.elytra.NetherPath;
import baritone.behavior.elytra.PathCalculationException;
import baritone.behavior.elytra.UnpackedSegment;
import baritone.cache.FasterWorldScanner;
import baritone.utils.BlockStateInterface;
import baritone.utils.accessor.IEntityFireworkRocket;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.Chunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;

public final class ElytraBehavior extends Behavior implements IElytraBehavior, Helper {

    /**
     * 2b2t seed
     */
    private static final long NETHER_SEED = 146008555100680L;

    // Used exclusively for PathRenderer
    public List<Pair<Vec3d, Vec3d>> clearLines;
    public List<Pair<Vec3d, Vec3d>> blockedLines;
    public List<Vec3d> simulationLine;
    public BlockPos aimPos;
    public List<BetterBlockPos> visiblePath;

    // :sunglasses:
    private final NetherPathfinderContext context;
    private final PathManager pathManager;
    private final ElytraProcess process;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;

    private BlockStateInterface bsi;
    private BlockPos destination;

    private Future<Solution> solver;
    private Solution pendingSolution;
    private boolean deployedFireworkLastTick;
    private final int[] nextTickBoostCounter;
    private boolean solveNextTick;

    public ElytraBehavior(Baritone baritone) {
        super(baritone);
        this.context = new NetherPathfinderContext(NETHER_SEED);
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.visiblePath = Collections.emptyList();
        this.pathManager = this.new PathManager();
        this.process = new ElytraProcess();
        this.nextTickBoostCounter = new int[2];
    }

    @Override
    public void onLoad() {
        baritone.getPathingControlManager().registerProcess(this.process);
    }

    private final class PathManager {

        private NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isElytraFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        public void pathToDestination() {
            this.pathToDestination(ctx.playerFeet());
        }

        public void pathToDestination(final BlockPos from) {
            final long start = System.nanoTime();
            this.path0(from, ElytraBehavior.this.destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logDirect(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logDirect(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public CompletableFuture<Void> pathRecalcSegment(final int upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = this.path.subList(upToIncl, this.path.size());
            final boolean complete = this.completePath;

            return this.path0(ctx.playerFeet(), this.path.get(upToIncl), segment -> segment.append(after.stream(), complete))
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to recompute segment");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);
            final long start = System.nanoTime();

            this.path0(this.path.get(afterIncl), ElytraBehavior.this.destination, segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = this.path.get(0).distanceTo(this.path.get(recompute));

                        if (this.completePath) {
                            logDirect(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logDirect(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute next segment");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        private void setPath(final UnpackedSegment segment) {
            this.path = segment.collect();
            this.completePath = segment.isFinished();
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return ElytraBehavior.this.context.pathFindAsync(src, dst)
                    .thenApply(UnpackedSegment::from)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::addScheduledTask);
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && ctx.world().isBlockLoaded(path.get(rangeEndExcl), false)) {
                rangeEndExcl++;
            }
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            if (!passable(ctx.world().getBlockState(path.get(rangeStartIncl)), false)) {
                // we're in a wall
                return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
            }

            if (this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(rangeEndExcl - 1)
                        .thenRun(() -> {
                            logDirect("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.path.getVec(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // find the next valid segment
                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rangeEndExcl - 1));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rangeEndExcl - 1)
                            .thenRun(() -> {
                                logDirect(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating) {
                return;
            }

            final int last = this.path.size() - 1;
            if (!this.completePath && ctx.world().isBlockLoaded(this.path.get(last), false)) {
                this.pathNextSegment(last);
            }
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            int index = this.playerNear;
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (event.isPostPopulate()) {
            final Chunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            this.context.queueForPacking(chunk);
        }
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        event.getAffectedChunks().stream()
                .map(pos -> ctx.world().getChunk(pos.x, pos.z))
                .forEach(this.context::queueForPacking);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof SPacketPlayerPosLook) {
            ctx.minecraft().addScheduledTask(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
            });
        }
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.destination = destination;
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isElytraFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    @Override
    public void cancel() {
        this.destination = null;
        this.visiblePath = Collections.emptyList();
        this.pathManager.clear();
        this.aimPos = null;
        this.remainingFireworkTicks = 0;
        this.remainingSetBackTicks = 0;
        if (this.solver != null) {
            this.solver.cancel(true);
            this.solver = null;
        }
        this.pendingSolution = null;
        Arrays.fill(this.nextTickBoostCounter, 0);
    }

    @Override
    public boolean isActive() {
        return !this.pathManager.getPath().isEmpty();
    }

    @Override
    public void onTick(final TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }

        // Fetch the previous solution, regardless of if it's going to be used
        this.pendingSolution = null;
        if (this.solver != null) {
            try {
                this.pendingSolution = this.solver.get();
            } catch (Exception ignored) {
                // it doesn't matter if get() fails since the solution can just be recalculated synchronously
            } finally {
                this.solver = null;
            }
        }

        // Certified mojang employee incident
        if (this.remainingFireworkTicks > 0) {
            this.remainingFireworkTicks--;
        }
        if (this.remainingSetBackTicks > 0) {
            this.remainingSetBackTicks--;
        }
        if (!this.getAttachedFirework().isPresent()) {
            this.minimumBoostTicks = 0;
        }

        // Reset rendered elements
        this.clearLines.clear();
        this.blockedLines.clear();
        this.simulationLine = null;
        this.aimPos = null;

        final List<BetterBlockPos> path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return;
        }

        this.bsi = new BlockStateInterface(ctx);
        this.pathManager.tick();

        final int playerNear = this.pathManager.getNear();
        this.visiblePath = path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        );
    }

    /**
     * Called by {@link ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    private void tick() {
        if (this.pathManager.getPath().isEmpty()) {
            return;
        }

        if (ctx.player().collidedHorizontally) {
            logDirect("hbonk");
        }
        if (ctx.player().collidedVertically) {
            logDirect("vbonk");
        }

        final SolverContext solverContext = this.new SolverContext(false);
        this.solveNextTick = true;

        // If there's no previously calculated solution to use, or the context used at the end of last tick doesn't match this tick
        final Solution solution;
        if (this.pendingSolution == null || !this.pendingSolution.context.equals(solverContext)) {
            solution = this.solveAngles(solverContext);
        } else {
            solution = this.pendingSolution;
        }

        if (this.deployedFireworkLastTick) {
            this.nextTickBoostCounter[solverContext.boost.isBoosted() ? 1 : 0]++;
            this.deployedFireworkLastTick = false;
        }

        if (solution == null) {
            logDirect("no solution");
            return;
        }

        baritone.getLookBehavior().updateTarget(solution.rotation, false);

        if (!solution.solvedPitch) {
            logDirect("no pitch solution, probably gonna crash in a few ticks LOL!!!");
            return;
        } else {
            this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
        }

        this.tickUseFireworks(
                solution.context.start,
                solution.goingTo,
                solution.context.boost.isBoosted(),
                solution.forceUseFirework
        );
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            // TODO: If we used a firework at the end of the last tick, then it's worth it to solve with the assumption
            //       that we'll be boosted next tick (if our ping to the server is less than 50ms) since a recalc won't
            //       be needed on the main thread.
            final SolverContext context = this.new SolverContext(true);
            this.solver = CompletableFuture.supplyAsync(() -> this.solveAngles(context));
            this.solveNextTick = false;
        }
    }

    private Solution solveAngles(final SolverContext context) {
        final NetherPath path = context.path;
        final int playerNear = context.playerNear;
        final Vec3d start = context.start;

        final boolean isInLava = ctx.player().isInLava();
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                final List<Pair<Vec3d, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i).add(0, dy, 0), dy));
                    } else if (relaxation < 2) {
                        final double[] interps = relaxation == 0
                                ? new double[]{1.0, 0.75, 0.5, 0.25}
                                : new double[]{1.0, 0.875, 0.75, 0.625, 0.5, 0.375, 0.25, 0.125};
                        for (double interp : interps) {
                            final Vec3d dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest.add(0, dy, 0), dy));
                        }
                    } else {
                        // Create a point along the segment every 0.5 blocks
                        final Vec3d delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final double stepLength = 0.5;
                        final int steps = MathHelper.floor(delta.length() / stepLength);
                        final Vec3d step = delta.normalize().scale(stepLength);

                        Vec3d stepped = path.getVec(i).add(0, dy, 0);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3d, Integer> candidate : candidates) {
                    final Vec3d dest = candidate.first();
                    final Integer augment = candidate.second();

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(start, dest, growth, isInLava)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(context, dest, relaxation, isInLava);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
                    }
                }
            }
        }
        return solution;
    }

    private void tickUseFireworks(final Vec3d start, final Vec3d goingTo, final boolean isBoosted, final boolean forceUseFirework) {
        if (this.remainingSetBackTicks > 0) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        final boolean useOnDescend = !Baritone.settings().conserveFireworks.value || ctx.player().posY < goingTo.y + 5;
        final double currentSpeed = new Vec3d(
                ctx.player().motionX,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().posY < goingTo.y ? Math.max(0, ctx.player().motionY) : ctx.player().motionY,
                ctx.player().motionZ
        ).lengthSquared();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && useOnDescend
                && (ctx.player().posY < goingTo.y - 5 || start.distanceTo(new Vec3d(goingTo.x + 0.5, ctx.player().posY, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            // Prioritize boosting fireworks over regular ones
            // TODO: Take the minimum boost time into account?
            if (!baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isBoostingFireworks) &&
                    !baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
                logDirect("no fireworks");
                return;
            }
            logDirect("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), EnumHand.MAIN_HAND);
            this.minimumBoostTicks = 10 * (1 + getFireworkBoost(ctx.player().getHeldItemMainhand()).orElse(0));
            this.remainingFireworkTicks = 10;
            this.deployedFireworkLastTick = true;
        }
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3d start;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;

        public SolverContext(boolean async) {
            this.path       = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();
            this.start      = ElytraBehavior.this.ctx.playerFeetAsVec();

            final Integer fireworkTicksExisted;
            if (async && ElytraBehavior.this.deployedFireworkLastTick) {
                final int[] counter = ElytraBehavior.this.nextTickBoostCounter;
                fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
            } else {
                fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.ticksExisted).orElse(null);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            this.maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3d> steps;

        public PitchResult(float pitch, double dot, List<Vec3d> steps) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3d goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3d goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    private static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORKS) {
            return false;
        }
        // If it has NBT data, make sure it won't cause us to explode.
        final NBTTagCompound compound = itemStack.getSubCompound("Fireworks");
        return compound == null || !compound.hasKey("Explosions");
    }

    private static boolean isBoostingFireworks(final ItemStack itemStack) {
        return getFireworkBoost(itemStack).isPresent();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        if (isFireworks(itemStack)) {
            final NBTTagCompound compound = itemStack.getSubCompound("Fireworks");
            if (compound != null && compound.hasKey("Flight")) {
                return OptionalInt.of(compound.getByte("Flight"));
            }
        }
        return OptionalInt.empty();
    }

    private Optional<EntityFireworkRocket> getAttachedFirework() {
        return ctx.world().loadedEntityList.stream()
            .filter(x -> x instanceof EntityFireworkRocket)
            .filter(x -> Objects.equals(((IEntityFireworkRocket) x).getBoostedEntity(), ctx.player()))
            .map(x -> (EntityFireworkRocket) x)
            .findFirst();
    }

    private boolean isHitboxClear(final Vec3d start, final Vec3d dest, final Double growAmount, boolean ignoreLava) {
        if (!this.clearView(start, dest, ignoreLava)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AxisAlignedBB bb = ctx.player().getEntityBoundingBox().grow(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        // Use non-batching method without early failure
        if (Baritone.settings().renderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3d s = new Vec3d(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3d d = new Vec3d(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false)) {
                    clear = false;
                }
            }
            return clear;
        }

        return this.context.raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    private boolean clearView(Vec3d start, Vec3d dest, boolean ignoreLava) {
        final boolean clear;
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || this.context.raytrace(start, dest);
        } else {
            clear = ctx.world().rayTraceBlocks(start, dest, false, false, false) == null;
        }

        if (clear) {
            this.clearLines.add(new Pair<>(start, dest));
            return true;
        } else {
            this.blockedLines.add(new Pair<>(start, dest));
            return false;
        }
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(MathHelper.ceil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private Pair<Float, Boolean> solvePitch(final SolverContext context, final Vec3d goal,
                                            final int relaxation, final boolean ignoreLava) {

        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay, ignoreLava);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
                tests.add(new IntTriple(5, 5, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (relaxation > 0) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new Pair<>(resultBoost.get().pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3d goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay, final boolean ignoreLava) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3d goalDelta = goal.subtract(context.start);
        final Vec3d goalDirection = goalDelta.normalize();

        PitchResult result = null;

        outer:
        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3d> displacement = this.simulate(
                    context.aimProcessor.fork(),
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay,
                    ignoreLava
            );
            if (displacement == null) {
                continue;
            }
            final int lastIndex = displacement.size() - 1;
            final Vec3d last = displacement.get(lastIndex);
            final double goodness = goalDirection.dotProduct(last.normalize());
            if (result == null || goodness > result.dot) {
                if (relaxation < 2) {
                    // Ensure that the goal is visible along the entire simulated path
                    // Reverse order iteration since the last position is most likely to fail
                    for (int i = lastIndex; i >= 1; i--) {
                        if (!clearView(context.start.add(displacement.get(i)), goal, false)) {
                            continue outer;
                        }
                    }
                } else {
                    // Ensure that the goal is visible from the final position
                    if (!clearView(context.start.add(last), goal, false)) {
                        continue;
                    }
                }
                result = new PitchResult(pitch, goodness, displacement);
            }
        }
        if (result != null) {
            this.simulationLine = result.steps;
        }
        return result;
    }

    private List<Vec3d> simulate(final ITickableAimProcessor aimProcessor, final Vec3d goalDelta, final float pitch,
                                 final int ticks, int ticksBoosted, final int ticksBoostDelay, final boolean ignoreLava) {
        Vec3d delta = goalDelta;
        Vec3d motion = ctx.playerMotion();
        AxisAlignedBB hitbox = ctx.player().getEntityBoundingBox();
        List<Vec3d> displacement = new ArrayList<>();
        displacement.add(Vec3d.ZERO);

        for (int i = 0; i < ticks; i++) {
            if (MC_1_12_Collision_Fix.bonk(ctx, hitbox)) {
                return null;
            }
            if (delta.lengthSquared() < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3d.ZERO, delta, ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3d lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            motion = step(motion, lookDirection, rotation.getPitch());
            delta = delta.subtract(motion);

            // Collision box while the player is in motion, with additional padding for safety
            final AxisAlignedBB inMotion = hitbox.expand(motion.x, motion.y, motion.z).grow(0.01);

            for (int x = MathHelper.floor(inMotion.minX); x < MathHelper.ceil(inMotion.maxX); x++) {
                for (int y = MathHelper.floor(inMotion.minY); y < MathHelper.ceil(inMotion.maxY); y++) {
                    for (int z = MathHelper.floor(inMotion.minZ); z < MathHelper.ceil(inMotion.maxZ); z++) {
                        if (!this.passable(x, y, z, ignoreLava)) {
                            return null;
                        }
                    }
                }
            }

            hitbox = hitbox.offset(motion);
            displacement.add(displacement.get(displacement.size() - 1).add(motion));

            if (i >= ticksBoostDelay && ticksBoosted-- > 0) {
                // See EntityFireworkRocket
                motion = motion.add(
                        lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5
                );
            }
        }

        return displacement;
    }

    private static Vec3d step(final Vec3d motion, final Vec3d lookDirection, final float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = MathHelper.cos(pitchRadians);
        //System.out.println("always the same lol " + -pitchBase + " " + pitchBase3);
        //System.out.println("always the same lol " + Math.abs(pitchBase3) + " " + pitchBase2);
        //System.out.println("always 1 lol " + thisIsAlwaysOne);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-MathHelper.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;
        //System.out.println(motionX + " " + motionY + " " + motionZ);

        return new Vec3d(motionX, motionY, motionZ);
    }

    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        return passable(this.bsi.get0(x, y, z), ignoreLava);
    }

    private static boolean passable(IBlockState state, boolean ignoreLava) {
        Material mat = state.getMaterial();
        return mat == Material.AIR || (ignoreLava && mat == Material.LAVA);
    }

    /**
     * Minecraft 1.12's pushOutOfBlocks logic doesn't account for players being able to fit under single block spaces,
     * so whenever the edge of a ceiling is encountered while elytra flying it tries to push the player out.
     */
    private static final class MC_1_12_Collision_Fix {

        public static boolean bonk(final IPlayerContext ctx, final AxisAlignedBB aabb) {
            final Vec3d center = aabb.getCenter();
            final double width = (double) ctx.player().width * 0.35D;
            final double x = center.x;
            final double y = aabb.minY + 0.5D;
            final double z = center.z;

            return pushOutOfBlocks(ctx, x - width, y, z + width)
                    || pushOutOfBlocks(ctx, x - width, y, z - width)
                    || pushOutOfBlocks(ctx, x + width, y, z - width)
                    || pushOutOfBlocks(ctx, x + width, y, z + width);
        }

        private static boolean pushOutOfBlocks(final IPlayerContext ctx, final double x, final double y, final double z) {
            final BlockPos pos = new BlockPos(x, y, z);
            if (isOpenBlockSpace(ctx, pos)) {
                return false;
            }
            return isOpenBlockSpace(ctx, pos.west())
                    || isOpenBlockSpace(ctx, pos.east())
                    || isOpenBlockSpace(ctx, pos.north())
                    || isOpenBlockSpace(ctx, pos.south());
        }

        private static boolean isOpenBlockSpace(IPlayerContext ctx, BlockPos pos) {
            return !ctx.world().getBlockState(pos).isNormalCube() && !ctx.world().getBlockState(pos.up()).isNormalCube();
        }
    }

    private final class ElytraProcess implements IBaritoneProcess {

        private State state;
        private Goal goal;

        @Override
        public boolean isActive() {
            return ElytraBehavior.this.destination != null;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            if (calcFailed) {
                onLostControl();
                logDirect("Failed to get to jump off spot, canceling");
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            if (ctx.player().isElytraFlying()) {
                this.state = State.FLYING;
                this.goal = null;
                baritone.getInputOverrideHandler().clearAllKeys();
                ElytraBehavior.this.tick();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            if (!Baritone.settings().elytraAutoJump.value) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            // We were flying, but we're not anymore, reset the state
            if (this.state == State.FLYING) {
                this.state = State.GET_TO_JUMP;
            }

            if (this.state == State.GET_TO_JUMP) {
                if (this.goal == null) {
                    final BlockPos jumpOff = this.findJumpOffSpot();
                    if (jumpOff == null) {
                        onLostControl();
                        logDirect("Couldn't find a suitable spot to jump off of, canceling");
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    this.goal = new GoalGetToBlock(jumpOff);
                    ElytraBehavior.this.pathManager.pathToDestination(jumpOff.add(0, -4, 0));
                }
                if (this.goal.isInGoal(ctx.playerFeet())) {
                    this.state = State.START_FLYING;
                } else {
                    return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
                }
            }

            if (this.state == State.START_FLYING && isSafeToCancel && !ElytraBehavior.this.pathManager.getPath().isEmpty()) {
                baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
                        ctx.playerHead(),
                        VecUtils.getBlockPosCenter(((IGoalRenderPos) this.goal).getGoalPos()),
                        ctx.playerRotations()
                ), false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                if (ctx.player().fallDistance > 0.0f) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        @Override
        public boolean isTemporary() {
            return false;
        }

        @Override
        public void onLostControl() {
            this.goal = null;
            this.state = State.GET_TO_JUMP;
            ElytraBehavior.this.cancel();
        }

        @Override
        public double priority() {
            return 10;
        }

        @Override
        public String displayName0() {
            return "Elytra";
        }

        // ok... now this.. is disgusting
        // TODO: make less scuffed
        private BlockPos findJumpOffSpot() {
            BlockPos best = null;
            final BetterBlockPos feet = ctx.playerFeet();
            final List<ChunkPos> nearby = FasterWorldScanner.getChunkRange(feet.x >> 4, feet.z >> 4, 3);
            for (ChunkPos pos : nearby) {
                final Chunk chunk = ctx.world().getChunk(pos.x, pos.z);
                int[][] obstruction = new int[16][16];
                for (int y0 = 0; y0 < 8; y0++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int y = feet.y - y0;
                            if (chunk.getBlockState(x, y, z).getMaterial() != Material.AIR) {
                                if (obstruction[x][z] == 0 || obstruction[x][z] > y0 + 1) {
                                    obstruction[x][z] = y0 + 1;
                                }
                            }
                        }
                    }
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (obstruction[x][z] != 0) {
                            continue;
                        }

                        final int[] adjacent = new int[4];
                        if (x > 0)  adjacent[0] = obstruction[x - 1][z];
                        if (z > 0)  adjacent[1] = obstruction[x][z - 1];
                        if (x < 15) adjacent[2] = obstruction[x + 1][z];
                        if (z < 15) adjacent[3] = obstruction[x][z + 1];
                        final OptionalInt minLevel = Arrays.stream(adjacent).filter(i -> i != 0).min();

                        if (minLevel.isPresent() && minLevel.getAsInt() <= 4) {
                            final int yActual = feet.y - minLevel.getAsInt() + 2; // lol
                            // The target spot itself is clear
                            if (chunk.getBlockState(x, yActual, z).getMaterial() != Material.AIR
                                    || chunk.getBlockState(x, yActual + 1, z).getMaterial() != Material.AIR) {
                                continue;
                            }
                            // lessgooo
                            final BlockPos target = new BlockPos(chunk.x << 4 | x, yActual, chunk.z << 4 | z);
                            if (best == null || target.distanceSq(feet) < best.distanceSq(feet)) {
                                best = target;
                            }
                        }
                    }
                }
            }
            return best;
        }
    }

    private enum State {
        GET_TO_JUMP,
        START_FLYING,
        FLYING
    }
}