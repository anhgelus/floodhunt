package world.anhgelus.floodhunt.timer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.util.List;

public interface TimerAccess {
	/**
	 * Get the timer linked to the overworld
	 *
	 * @param server Current server
	 * @return TimerAccess linked to the overworld
	 */
	static TimerAccess getTimerFromOverworld(MinecraftServer server) {
		final var timer = (TimerAccess) server.getWorld(World.OVERWORLD);
		if (timer == null)
			throw new NullPointerException("Impossible to get TimerAccess from the overworld (it is null)");
		return timer;
	}

	/**
	 * Run a task (called each tick ticked)
	 *
	 * @param task Task to run
	 */
	void floodhunt_runTask(TimerAccess.TickTask task);

	void floodhunt_cancel();

	/**
	 * @return All non-cancelled tasks
	 */
	List<TickTask> floodhunt_getTasks();

	interface TickTask {
		/**
		 * Tick the task
		 */
		void tick();

		/**
		 * Cancel the task
		 *
		 * @return the remaining ticks before the run of the Task
		 * @throws IllegalStateException if the task is already canceled
		 */
		long cancel();

		boolean isRunning();

		/**
		 * @return the number of ticks before run of the task (if the task is canceled, returns -1)
		 */
		long getTickingBeforeRun();
	}

	/**
	 * Represents a task to run after ticking
	 */
	@FunctionalInterface
	interface Task {
		void run();
	}
}
