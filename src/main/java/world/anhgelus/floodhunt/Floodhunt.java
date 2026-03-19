package world.anhgelus.floodhunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRuleCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.anhgelus.floodhunt.config.Config;
import world.anhgelus.floodhunt.config.ConfigPayload;
import world.anhgelus.floodhunt.config.SimpleConfig;
import world.anhgelus.floodhunt.game.Game;
import world.anhgelus.floodhunt.game.GamePayload;

import java.util.HashMap;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;


public class Floodhunt implements ModInitializer {

	public static final String MOD_ID = "floodhunt";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final SimpleConfig CONFIG_FILE = Config.configFile(MOD_ID);
	public static final GameRule<Integer> GAME_DURATION = createGameRule("game_duration", 90, 0);
	public static final GameRule<Integer> MOLE_PERCENTAGE = createGameRule("flood_percentage", 25, 0, 100);
	public static final GameRule<Integer> MOLE_COUNT = createGameRule("flood_count", -1, -1);
	public static final GameRule<Boolean> SHOW_NAMETAGS = createGameRule("show_nametags", false);
	public static final GameRule<Boolean> SHOW_TAB = createGameRule("show_tab", false);
	public static final GameRule<Boolean> SHOW_SKINS = createGameRule("show_skins", false);
	public static final GameRule<Integer> INITIAL_WORLD_SIZE = createGameRule("initial_world_size", 600, 0);
	public static final GameRule<Integer> FINAL_WORLD_SIZE = createGameRule("final_world_size", 100, 0);
	public static final GameRule<Integer> MOVING_STARTING_TIME_OFFSET = createGameRule("border_moving_starting_time_offset", 30, 0);
	public static final GameRule<Boolean> ENABLE_PORTALS = createGameRule("enable_portals", false);
	public static final GameRule<Boolean> FOOD_ON_START = createGameRule("food_on_start", true);
	public static Config CONFIG;
	public static HashMap<UUID, Boolean> timerVisibility = new HashMap<>();

	static {
		GameRuleEvents.changeCallback(SHOW_NAMETAGS).register(Floodhunt::sendConfigPayload);
		GameRuleEvents.changeCallback(SHOW_TAB).register(Floodhunt::sendConfigPayload);
		GameRuleEvents.changeCallback(SHOW_SKINS).register(Floodhunt::sendConfigPayload);
	}

	public Game game;

	private static <T> GameRule<T> createGameRule(String key, GameRuleBuilder<T> builder) {
		return builder.category(GameRuleCategory.MISC).buildAndRegister(Identifier.of(MOD_ID, key));
	}

	private static GameRule<Boolean> createGameRule(String key, boolean def) {
		return createGameRule(key, GameRuleBuilder.forBoolean(CONFIG_FILE.getOrDefault(key, def)));
	}

	private static GameRule<Integer> createGameRule(String key, int def, int min) {
		return createGameRule(key, GameRuleBuilder.forInteger(CONFIG_FILE.getOrDefault(key, def)).minValue(min));
	}

	private static GameRule<Integer> createGameRule(String key, int def, int min, int max) {
		return createGameRule(key, GameRuleBuilder.forInteger(CONFIG_FILE.getOrDefault(key, def)).range(min, max));
	}

	private static <T> void sendConfigPayload(T v, MinecraftServer server) {
		if (CONFIG == null) return;
		CONFIG.sendConfigPayload();
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Floodhunt");

		final var command = literal("floodhunt");
		command.then(literal("start")
			.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
			.executes(context -> {
				game = new Game(context.getSource().getServer());
				game.start();
				return Command.SINGLE_SUCCESS;
			}));
		command.then(literal("timer").requires(ServerCommandSource::isExecutedByPlayer).then(
			literal("show").executes(context -> {
				var player = context.getSource().getPlayer();
				assert player != null;

				timerVisibility.put(player.getUuid(), true);
				context.getSource().sendFeedback(() -> Text.translatable("commands.floodhunt.timer.show"), false);

				if (game == null || !game.started()) {
					player.networkHandler.sendPacket(new OverlayMessageS2CPacket(
						Text.translatable("commands.floodhunt.error.game_not_started").formatted(Formatting.RED)
					));
				} else {
					player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.of(game.getRemainingText())));
				}

				return Command.SINGLE_SUCCESS;
			})
		).then(
			literal("hide").executes(context -> {
				var player = context.getSource().getPlayer();
				assert player != null;

				timerVisibility.put(player.getUuid(), false);
				context.getSource().sendFeedback(() -> Text.translatable("commands.floodhunt.timer.hide"), false);
				return Command.SINGLE_SUCCESS;
			})
		));
		command.then(literal("role")
			.requires(ServerCommandSource::isExecutedByPlayer)
			.executes(context -> {
				if (game == null || !game.started()) {
					throw (new SimpleCommandExceptionType(Text.translatable("commands.floodhunt.error.game_not_started"))).create();
				}

				final var source = context.getSource();
				final var player = source.getPlayer();
				assert player != null;

				if (game.isMole(player)) {
					source.sendFeedback(
						() -> Text.translatable("commands.floodhunt.role.mole")
							.append("\n\n")
							.append(Text.translatable("commands.floodhunt.role.mole.list", game.getMolesAsString())),
						false);
				} else if (player.isSpectator()) {
					source.sendFeedback(
						() -> Text.translatable("commands.floodhunt.role.survivor.mole_count", game.getMolesCount()),
						false);
				} else {
					source.sendFeedback(
						() -> Text.translatable("commands.floodhunt.role.survivor")
							.append("\n\n")
							.append(Text.translatable("commands.floodhunt.role.survivor.mole_count", game.getMolesCount())),
						false);
				}

				return Command.SINGLE_SUCCESS;
			}));
		command.then(literal("stop")
			.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
			.executes(context -> {
				if (game == null || !game.started()) {
					throw (new SimpleCommandExceptionType(Text.translatable("commands.floodhunt.error.game_not_started"))).create();
				}

				game.stop();

				return Command.SINGLE_SUCCESS;
			}));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> CONFIG = new Config(server));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(command));

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> false);

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (!(entity instanceof ServerPlayerEntity) || game == null) return;
			if (!game.started()) return;
			if (game.wonByMoles()) game.end();
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (game == null) return;
			if (!game.started()) return;
			newPlayer.changeGameMode(GameMode.SPECTATOR);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayNetworking.send(
				handler.player,
				new ConfigPayload(CONFIG.nametagsEnabled(), CONFIG.skinsEnabled(), CONFIG.tabEnabled())
			);
			ServerPlayNetworking.send(
				handler.player,
				new GamePayload(game != null && game.started())
			);
		});

		PayloadTypeRegistry.playS2C().register(ConfigPayload.ID, ConfigPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(GamePayload.ID, GamePayload.CODEC);
	}
}
