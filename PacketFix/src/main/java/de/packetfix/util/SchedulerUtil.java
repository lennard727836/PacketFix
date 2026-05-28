package de.packetfix.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Consumer;

/**
 * Scheduler abstraction layer.
 *
 * Bukkit / Spigot / Paper / Purpur  → BukkitScheduler (sync main-thread tasks).
 * Folia                             → GlobalRegionScheduler (server-wide) and
 *                                     EntityScheduler (per-entity region tasks).
 *
 * Folia detection is done at class-load time via a Class.forName check.
 * All Folia API calls go through reflection so the compiled JAR does NOT
 * require Folia classes at compile time — a single JAR works everywhere.
 */
public final class SchedulerUtil {

    private static final boolean FOLIA;

    static {
        boolean f = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            f = true;
        } catch (ClassNotFoundException ignored) {}
        FOLIA = f;
    }

    private SchedulerUtil() {}

    public static boolean isFolia() { return FOLIA; }

    // -----------------------------------------------------------------------
    //  Global / server-wide tasks
    // -----------------------------------------------------------------------

    /** Run once on the next tick (main thread / global region). */
    public static void runTask(Plugin plugin, Runnable task) {
        if (FOLIA) {
            foliaGlobal("run", plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Run once after delayTicks (main thread / global region). */
    public static void runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (FOLIA) {
            foliaGlobalDelayed("runDelayed", plugin, task, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * Run a repeating task.
     * Returns an opaque handle; pass it to {@link #cancelTask(Object)} to stop.
     */
    public static Object runTaskTimer(Plugin plugin, Runnable task, long initialDelay, long period) {
        if (FOLIA) {
            return foliaGlobalTimer(plugin, task, Math.max(1L, initialDelay), period);
        } else {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, period);
        }
    }

    /** Cancel a task returned by {@link #runTaskTimer}. */
    public static void cancelTask(Object handle) {
        if (handle == null) return;
        try {
            if (handle instanceof BukkitTask) {
                ((BukkitTask) handle).cancel();
            } else {
                handle.getClass().getMethod("cancel").invoke(handle);
            }
        } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    //  Entity-region tasks (important for Folia correctness)
    // -----------------------------------------------------------------------

    /** Run a task on the region owning the entity. */
    public static void runTaskForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (FOLIA) {
            foliaEntity(entity, plugin, task, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Run a task on the entity's region after delayTicks. */
    public static void runTaskLaterForEntity(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (FOLIA) {
            foliaEntityDelayed(entity, plugin, task, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    // -----------------------------------------------------------------------
    //  Reflective Folia helpers (all API calls via reflection)
    // -----------------------------------------------------------------------

    private static void foliaGlobal(String method, Plugin plugin, Runnable task) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Consumer<Object> consumer = t -> task.run();
            scheduler.getClass().getMethod(method, Plugin.class, Consumer.class)
                    .invoke(scheduler, plugin, consumer);
        } catch (Exception e) {
            Bukkit.getScheduler().runTask(plugin, task); // safe fallback
        }
    }

    private static void foliaGlobalDelayed(String method, Plugin plugin, Runnable task, long delay) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Consumer<Object> consumer = t -> task.run();
            scheduler.getClass().getMethod(method, Plugin.class, Consumer.class, long.class)
                    .invoke(scheduler, plugin, consumer, delay);
        } catch (Exception e) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    private static Object foliaGlobalTimer(Plugin plugin, Runnable task, long initial, long period) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Consumer<Object> consumer = t -> task.run();
            return scheduler.getClass()
                    .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class)
                    .invoke(scheduler, plugin, consumer, initial, period);
        } catch (Exception e) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, initial, period);
        }
    }

    private static void foliaEntity(Entity entity, Plugin plugin, Runnable task,
                                     Runnable retired, long delay) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Consumer<Object> consumer = t -> task.run();
            scheduler.getClass()
                    .getMethod("run", Plugin.class, Consumer.class, Runnable.class)
                    .invoke(scheduler, plugin, consumer, retired);
        } catch (Exception e) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static void foliaEntityDelayed(Entity entity, Plugin plugin, Runnable task, long delay) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Consumer<Object> consumer = t -> task.run();
            scheduler.getClass()
                    .getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class)
                    .invoke(scheduler, plugin, consumer, (Runnable) null, delay);
        } catch (Exception e) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
    }
}
