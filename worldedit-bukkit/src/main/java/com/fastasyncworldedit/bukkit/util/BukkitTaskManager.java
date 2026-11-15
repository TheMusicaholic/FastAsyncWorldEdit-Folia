package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.util.FoliaUtil;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitEntity;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extension.platform.PlayerProxy;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class BukkitTaskManager extends TaskManager {

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final boolean folia = FoliaUtil.isFoliaServer();

    /**
     * Folia schedulers do not hand out integer ids. Track repeating tasks so that {@link #cancel(int)} works and tasks
     * do not leak forever.
     */
    private final Map<Integer, ScheduledTask> foliaTasks = new ConcurrentHashMap<>();
    private final AtomicInteger foliaTaskIds = new AtomicInteger(1);

    public BukkitTaskManager(final Plugin plugin) {
        this.plugin = plugin;
    }

    private int track(ScheduledTask task) {
        int id = foliaTaskIds.getAndIncrement();
        foliaTasks.put(id, task);
        return id;
    }

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        if (folia) {
            long period = Math.max(1, interval);
            return track(this.plugin.getServer().getGlobalRegionScheduler()
                    .runAtFixedRate(this.plugin, scheduledTask -> runnable.run(), period, period));
        }
        return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, runnable, interval, interval);
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        if (folia) {
            long period = Math.max(1, interval) * MILLIS_PER_TICK;
            return track(this.plugin.getServer().getAsyncScheduler()
                    .runAtFixedRate(this.plugin, scheduledTask -> runnable.run(), period, period, TimeUnit.MILLISECONDS));
        }
        return this.plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(this.plugin, runnable, interval, interval);
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        if (folia) {
            this.plugin.getServer().getAsyncScheduler().runNow(this.plugin, scheduledTask -> runnable.run());
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, runnable);
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        if (folia) {
            this.plugin.getServer().getGlobalRegionScheduler().execute(this.plugin, runnable);
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, runnable);
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        if (folia) {
            if (delay <= 0) {
                this.plugin.getServer().getGlobalRegionScheduler().execute(this.plugin, runnable);
            } else {
                this.plugin.getServer().getGlobalRegionScheduler().runDelayed(this.plugin, scheduledTask -> runnable.run(), delay);
            }
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, runnable, delay);
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        if (folia) {
            if (delay <= 0) {
                this.plugin.getServer().getAsyncScheduler().runNow(this.plugin, scheduledTask -> runnable.run());
            } else {
                this.plugin.getServer().getAsyncScheduler()
                        .runDelayed(this.plugin, scheduledTask -> runnable.run(), delay * MILLIS_PER_TICK, TimeUnit.MILLISECONDS);
            }
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, runnable, delay);
    }

    @Override
    public void cancel(final int task) {
        if (task == -1) {
            return;
        }
        if (folia) {
            ScheduledTask scheduled = foliaTasks.remove(task);
            if (scheduled != null) {
                scheduled.cancel();
            }
            return;
        }
        Bukkit.getScheduler().cancelTask(task);
    }

    //FAWE start - region-aware scheduling (Folia / Canvas)

    @Override
    public boolean isOwnedByCurrentRegion(@Nonnull final World world, final int chunkX, final int chunkZ) {
        if (!folia) {
            return super.isOwnedByCurrentRegion(world, chunkX, chunkZ);
        }
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        return bukkitWorld != null && Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ);
    }

    @Override
    public <T> T syncAt(@Nonnull final World world, final int chunkX, final int chunkZ, @Nonnull final Supplier<T> supplier) {
        if (!folia) {
            return super.syncAt(world, chunkX, chunkZ, supplier);
        }
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        if (bukkitWorld == null || Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getRegionScheduler().execute(plugin, bukkitWorld, chunkX, chunkZ, () -> complete(future, supplier));
        } catch (IllegalPluginAccessException e) {
            // Plugin is disabling: the scheduler no longer accepts tasks. Best effort.
            return supplier.get();
        }
        return join(future);
    }

    @Override
    public void taskAt(@Nonnull final World world, final int chunkX, final int chunkZ, @Nonnull final Runnable runnable) {
        if (!folia) {
            super.taskAt(world, chunkX, chunkZ, runnable);
            return;
        }
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        if (bukkitWorld == null || Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            runnable.run();
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, bukkitWorld, chunkX, chunkZ, runnable);
    }

    @Override
    public <T> T syncWith(@Nonnull final Entity entity, @Nonnull final Supplier<T> supplier) {
        if (!folia) {
            return super.syncWith(entity, supplier);
        }
        org.bukkit.entity.Entity bukkitEntity = toBukkit(entity);
        if (bukkitEntity == null) {
            return syncAtLocation(entity, supplier);
        }
        return syncWith(bukkitEntity, supplier, null);
    }

    @Override
    public void taskWith(@Nonnull final Entity entity, @Nonnull final Runnable runnable) {
        if (!folia) {
            super.taskWith(entity, runnable);
            return;
        }
        org.bukkit.entity.Entity bukkitEntity = toBukkit(entity);
        if (bukkitEntity == null) {
            Location location = entity.getLocation();
            if (location.getExtent() instanceof World world) {
                taskAt(world, location.getBlockX() >> 4, location.getBlockZ() >> 4, runnable);
            } else {
                task(runnable);
            }
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(bukkitEntity)) {
            runnable.run();
            return;
        }
        bukkitEntity.getScheduler().run(plugin, task -> runnable.run(), null);
    }

    /**
     * Run a task on the thread owning a Bukkit entity and wait for the result.
     *
     * @param entity   the entity
     * @param supplier the task
     * @param retired  value to return if the entity was removed before the task could run
     * @return the result
     */
    public <T> T syncWith(@Nonnull final org.bukkit.entity.Entity entity, @Nonnull final Supplier<T> supplier, @Nullable T retired) {
        if (!folia) {
            return sync(supplier);
        }
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        ScheduledTask scheduled;
        try {
            // The retired callback runs if the entity is removed before the task gets to run
            scheduled = entity.getScheduler().run(plugin, task -> complete(future, supplier), () -> future.complete(retired));
        } catch (IllegalPluginAccessException e) {
            // Plugin is disabling: the scheduler no longer accepts tasks. Best effort.
            return supplier.get();
        }
        if (scheduled == null) {
            // Entity is already retired
            return retired;
        }
        return join(future);
    }

    /**
     * Run a task on the thread owning the block at the given Bukkit location and wait for the result.
     */
    public <T> T syncAt(@Nonnull final org.bukkit.Location location, @Nonnull final Supplier<T> supplier) {
        if (!folia) {
            return sync(supplier);
        }
        org.bukkit.World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (world == null || Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> complete(future, supplier));
        return join(future);
    }

    private <T> T syncAtLocation(Entity entity, Supplier<T> supplier) {
        Location location = entity.getLocation();
        if (location.getExtent() instanceof World world) {
            return syncAt(world, location.getBlockX() >> 4, location.getBlockZ() >> 4, supplier);
        }
        return supplier.get();
    }

    @Nullable
    private static org.bukkit.entity.Entity toBukkit(Entity entity) {
        if (entity instanceof com.sk89q.worldedit.entity.Player player) {
            com.sk89q.worldedit.entity.Player unwrapped = PlayerProxy.unwrap(player);
            if (unwrapped instanceof BukkitPlayer bukkitPlayer) {
                return bukkitPlayer.getPlayer();
            }
            return null;
        }
        if (entity instanceof BukkitEntity bukkitEntity) {
            return bukkitEntity.getBukkitEntity();
        }
        return null;
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> supplier) {
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    //FAWE end

}
