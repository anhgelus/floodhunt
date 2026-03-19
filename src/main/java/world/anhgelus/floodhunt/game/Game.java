package world.anhgelus.floodhunt.game;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;
import world.anhgelus.floodhunt.Floodhunt;
import world.anhgelus.floodhunt.timer.TickTask;
import world.anhgelus.floodhunt.timer.TimerAccess;
import world.anhgelus.floodhunt.utils.TimeUtils;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Game {

	public final int defaultTime = Floodhunt.CONFIG.getGameDuration() * 60;
	private final MinecraftServer server;
	private final Set<UUID> infected = new HashSet<>();
	private final TitleFadeS2CPacket timing = new TitleFadeS2CPacket(20, 40, 20);
	private int remaining = defaultTime;
	private boolean started = false;

	public Game(MinecraftServer server) {
		this.server = server;
	}

	public void start() {
		final int n = Floodhunt.CONFIG.getFloodCount() < 0
			? Math.floorDiv(server.getCurrentPlayerCount(), Math.floorDiv(100, Floodhunt.CONFIG.getFloodPercentage()))
			: Floodhunt.CONFIG.getFloodCount();

		final var playerManager = server.getPlayerManager();

		final var players = new ArrayList<>(playerManager.getPlayerList());
		for (int i = 0; i < n && !players.isEmpty(); i++) {
			final var r = ThreadLocalRandom.current().nextInt(0, players.size());
			final var infect = players.get(r);
			if (infect == null) throw new IllegalStateException("Mole is null!");
			infected.add(infect.getUuid());
			players.remove(r);
		}

		final var gamerules = server.getOverworld().getGameRules();
		// immutable gamerules
		gamerules.setValue(GameRules.SHOW_DEATH_MESSAGES, false, server);
		gamerules.setValue(GameRules.ANNOUNCE_ADVANCEMENTS, false, server);
		gamerules.setValue(GameRules.DO_IMMEDIATE_RESPAWN, true, server);

		final var timer = TimerAccess.getTimerFromOverworld(server);

		final var worldBorder = server.getOverworld().getWorldBorder();
		worldBorder.setSize(Floodhunt.CONFIG.getInitialWorldSize());
		if (Floodhunt.CONFIG.getBorderShrinkingStartingTimeOffset() < Floodhunt.CONFIG.getGameDuration()) {
			timer.floodhunt_runTask(new TickTask(() -> worldBorder.interpolateSize(
				Floodhunt.CONFIG.getInitialWorldSize(),
				Floodhunt.CONFIG.getFinalWorldSize(),
				(Floodhunt.CONFIG.getGameDuration() - Floodhunt.CONFIG.getBorderShrinkingStartingTimeOffset()) * 60 * 20L,
				0L
			), Floodhunt.CONFIG.getBorderShrinkingStartingTimeOffset() * 60 * 20L));
		}

		final var title = new TitleS2CPacket(Text.translatable("floodhunt.game.start.suspense"));
		playerManager.getPlayerList().forEach(p -> {
			p.getInventory().clear();
			p.kill(p.getEntityWorld());
			p.networkHandler.sendPacket(timing);
			p.networkHandler.sendPacket(title);
			p.changeGameMode(GameMode.SURVIVAL);
			if (Floodhunt.CONFIG.foodOnStart()) p.giveItemStack(new ItemStack(Items.COOKED_BEEF, 64));
		});

		server.setDefaultGameMode(GameMode.SPECTATOR);

		timer.floodhunt_runTask(new TickTask(() -> {
			playerManager.getPlayerList().forEach(p -> {
				p.networkHandler.sendPacket(timing);
				if (infected.contains(p.getUuid())) {
					p.networkHandler.sendPacket(new TitleS2CPacket(Text.translatable("floodhunt.game.start.mole.title")));
					p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("floodhunt.game.start.mole.subtitle")));
				} else {
					p.networkHandler.sendPacket(new TitleS2CPacket(Text.translatable("floodhunt.game.start.survivor.title")));
					p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("floodhunt.game.start.survivor.subtitle")));
				}
				// reset health and food level
				p.setHealth(p.getMaxHealth());
				p.getHungerManager().setFoodLevel(20);
				p.getHungerManager().setSaturationLevel(5.0f);
			});
			// reset time and weather
			server.getOverworld().setTimeOfDay(0);
			server.getOverworld().resetWeather();
			changeState(true);
			timer.floodhunt_runTask(new TickTask(() -> {
				remaining--;
				playerManager.getPlayerList().forEach(player -> {
					if (Floodhunt.timerVisibility.getOrDefault(player.getUuid(), true)) {
						player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.of(getRemainingText())));
					}
				});
				playerManager.sendToAll(timing);
				if (remaining == 0) end();
			}, 5 * 20, 20));
		}, 4 * 20));
	}

	public void stop() {
		server.getPlayerManager().broadcast(Text.translatable("commands.floodhunt.stop.success"), false);
		end();
	}

	private void end() {
		final var timer = TimerAccess.getTimerFromOverworld(server);
		timer.floodhunt_cancel();

		final var worldBorder = server.getOverworld().getWorldBorder();
		// Stops the border shrinking.
		worldBorder.setSize(worldBorder.getSize());

		changeState(false);
		final var pm = server.getPlayerManager();
		final var winnerSuspense = new TitleS2CPacket(Text.translatable("floodhunt.game.end.suspense.title"));
		pm.getPlayerList().forEach(p -> {
			p.networkHandler.sendPacket(timing);
			p.networkHandler.sendPacket(winnerSuspense);
			p.changeGameMode(GameMode.CREATIVE);
		});
		timer.floodhunt_runTask(new TickTask(() -> {
			TitleS2CPacket winner;
			if (wonByInfected()) {
				winner = new TitleS2CPacket(Text.translatable("floodhunt.game.end.winners.moles.title"));
			} else {
				winner = new TitleS2CPacket(Text.translatable("floodhunt.game.end.winners.survivors.title"));
			}
			pm.sendToAll(new SubtitleS2CPacket(Text.translatable("floodhunt.game.end.winners.subtitle", getMolesAsString())));
			pm.sendToAll(winner);
			pm.sendToAll(timing);
			infected.clear();
		}, 4 * 20));
	}

	public Text getRemainingText() {
		return Text.of("§c" + TimeUtils.generateShortString(remaining));
	}

	private Stream<ServerPlayerEntity> getInfected() {
		return infected.stream()
			.map(uuid -> server.getPlayerManager().getPlayer(uuid))
			.filter(Objects::nonNull)
			.filter(p -> !p.isSpectator() && !p.isCreative());
	}

	public int getInfectedCount() {
		return getInfected().toArray().length;
	}

	public String getMolesAsString() {
		return getInfected().map(PlayerEntity::getDisplayName)
			.filter(Objects::nonNull)
			.map(Object::toString)
			.collect(Collectors.joining(", "));
	}

	public boolean isInfected(ServerPlayerEntity player) {
		return infected.contains(player.getUuid());
	}

	public boolean wonByInfected() {
		final var moles = getInfected().map(PlayerEntity::getUuid).collect(Collectors.toSet());
		return !moles.isEmpty() && moles.containsAll(
			server.getPlayerManager()
				.getPlayerList()
				.stream()
				.filter(p -> !p.isSpectator() && !p.isCreative())
				.map(Entity::getUuid)
				.collect(Collectors.toSet())
		);
	}

	public boolean started() {
		return started;
	}

	private void changeState(boolean hasStarted) {
		started = hasStarted;
		final var payload = new GamePayload(hasStarted);
		server.getPlayerManager().sendToAll(ServerPlayNetworking.createS2CPacket(payload));
	}

	public void onRespawn(ServerPlayerEntity player) {
		infected.add(player.getUuid());
		if (wonByInfected()) end();
		player.changeGameMode(GameMode.SURVIVAL);
		player.sendMessage(Text.translatable("floodhunt.game.infected"));
		player.sendMessage(Text.translatable("floodhunt.game.start.mole.subtitle"));
	}
}
