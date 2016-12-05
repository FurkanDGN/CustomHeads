package cz.creeper.customheads;

import com.google.inject.Inject;
import cz.creeper.mineskinsponge.MineskinService;
import cz.creeper.mineskinsponge.SkinRecord;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.type.HandTypes;
import org.spongepowered.api.data.type.SkullTypes;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.Item;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.entity.spawn.EntitySpawnCause;
import org.spongepowered.api.event.cause.entity.spawn.SpawnTypes;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.item.inventory.transaction.InventoryTransactionResult;
import org.spongepowered.api.plugin.Dependency;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageReceiver;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Plugin(
        id = "customheads",
        name = "CustomHeads",
        description = "Create heads with custom textures with just a single command.",
        url = "https://github.com/Limeth/CustomHeads",
        authors = {
                "Limeth"
        },
        dependencies = {
                @Dependency(id = "mineskinsponge", version = "[1.1.1,)")
        }
)
public class CustomHeads {
    public static final String ARG_PATH = "File/URL";
    public static final String ARG_QUANTITY = "Quantity";
    public static final String ARG_PLAYER = "Player";
    @Inject
    private Logger logger;
    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDir;
    private PathResolver pathResolver;

    @Listener
    public void onGameInitialization(GameInitializationEvent event) {
        pathResolver = new PathResolver(this);
        registerCommands();
    }

    /**
     * Registers all of the fancy commands
     */
    public void registerCommands() {
        PluginContainer pluginContainer = getPluginContainer();
        String pluginId = pluginContainer.getId();
        CommandManager manager = Sponge.getCommandManager();

        CommandSpec giveCommand = CommandSpec.builder()
                .permission(pluginId + ".commands.give")
                .description(Text.of("Creates a custom head and gives it to the specified player."))
                .arguments(
                        GenericArguments.flags()
                                .valueFlag(
                                        GenericArguments.requiringPermission(
                                                GenericArguments.integer(Text.of(ARG_QUANTITY)),
                                                pluginId + ".commands.give." + ARG_QUANTITY.toLowerCase()
                                        ),
                                        "-quantity",
                                        "q"
                                )
                                .valueFlag(
                                        GenericArguments.requiringPermission(
                                                GenericArguments.player(Text.of(ARG_PLAYER)),
                                                pluginId + ".commands.give." + ARG_PLAYER.toLowerCase()
                                        ),
                                        "-player",
                                        "p"
                                )
                                .buildWith(GenericArguments.remainingJoinedStrings(Text.of(ARG_PATH)))
                )
                .executor(this::executeGive)
                .build();

        CommandSpec setCommand = CommandSpec.builder()
                .permission(pluginId + ".commands.set")
                .description(Text.of("Changes the skin of the held skull."))
                .arguments(
                        GenericArguments.remainingJoinedStrings(Text.of(ARG_PATH))
                )
                .executor(this::executeSet)
                .build();

        CommandSpec customHead = CommandSpec.builder()
                .permission(pluginId + ".commands")
                .description(Text.of("All of the fancy CustomHead commands."))
                .child(giveCommand, "give", "g")
                .child(setCommand, "set", "s")
                .build();

        manager.register(this, customHead, "customhead", "ch");
    }

    /**
     * The command executor for the `/customhead give` command.
     *
     * Creates and gives the player a player head with the specified texture.
     */
    public CommandResult executeGive(CommandSource src, CommandContext args) throws CommandException {
        String path = args.<String>getOne(ARG_PATH)
                .orElseThrow(() -> new IllegalStateException("The path should have been required."));
        int quantity = args.<Integer>getOne(ARG_QUANTITY).orElse(1);

        if (quantity <= 0)
            throw new CommandException(Text.of("The quantity must be positive."));

        Optional<Player> playerOptional = args.getOne(ARG_PLAYER);
        Player player;

        if (playerOptional.isPresent()) {
            player = playerOptional.get();
        } else if (src instanceof Player) {
            player = (Player) src;
        } else {
            throw new CommandException(Text.of("Please, specify the target player using the `--player` flag."));
        }

        // Downloads the texture and supplies a Path
        pathResolver.resolvePath(path).whenComplete((resolvedPath, e) -> {
            // Stop, if an error occured while downloading the texture
            if(checkError(e, player, path))
                return;

            // Sign the texture by Mojang using the MineskinService
            MineskinService mineskinService = getMineskinService();
            CompletableFuture<SkinRecord> skinRecordFuture = mineskinService.getSkin(resolvedPath);

            skinRecordFuture.thenAccept(skinRecord -> {
                // Create and give a custom player head with the specified texture to the player
                give(src, player, skinRecord.create(quantity));
                src.sendMessage(
                        Text.of(
                                TextColors.GREEN,
                                "Added " + quantity + " custom head(s) to ",
                                player.getDisplayNameData().displayName().get(),
                                "'s inventory."
                        )
                );
            });
        });

        return CommandResult.success();
    }

    /**
     * The command executor for the `/customhead set` command.
     *
     * Changes the skin of the held player head.
     */
    public CommandResult executeSet(CommandSource src, CommandContext args) throws CommandException {
        String path = args.<String>getOne(ARG_PATH)
                .orElseThrow(() -> new IllegalStateException("The path should have been required."));

        if(!(src instanceof Player))
            throw new CommandException(Text.of("Please, specify the target player using the `--player` flag."));

        Player player = (Player) src;

        // Do not proceed if the player isn't holding a player head in their main hand
        if(!checkHand(player).isPresent())
            return CommandResult.empty();

        player.sendMessage(Text.of(TextColors.GRAY, "Changing the skin, please wait..."));

        // Downloads the texture and supplies a Path
        pathResolver.resolvePath(path).whenComplete((resolvedPath, e) -> {
            // Stop, if an error occured while downloading the texture
            if(checkError(e, player, path))
                return;

            // Sign the texture by Mojang using the MineskinService
            MineskinService mineskinService = getMineskinService();
            CompletableFuture<SkinRecord> skinRecordFuture = mineskinService.getSkin(resolvedPath);

            skinRecordFuture.thenAccept(skinRecord -> {
                // Do not proceed if the player isn't holding a player head in their main hand
                Optional<ItemStack> itemStackOptional = checkHand(player);

                if(!itemStackOptional.isPresent())
                    return;

                ItemStack itemStack = itemStackOptional.get();

                // Change the skin of the held player head
                skinRecord.apply(itemStack);

                player.setItemInHand(HandTypes.MAIN_HAND, itemStack);
                player.sendMessage(
                        Text.of(TextColors.GREEN, "The skin of the held player head has been changed.")
                );
            });
        });

        return CommandResult.empty();
    }

    /**
     * Announces that an error has occurred while resolving a path.
     *
     * @param e The error
     * @param player Who to send the message to
     * @param path The requested path
     * @return Whether an error occurred and further execution should be terminated
     */
    private boolean checkError(Throwable e, MessageReceiver player, String path) {
        if(e != null) {
            TextException textException = (TextException) (e instanceof TextException ? e : (
                    e instanceof CompletionException && e.getCause() instanceof TextException ? e.getCause() : null));
            if (textException != null) {
                Text text = textException.getText();

                Sponge.getScheduler().createTaskBuilder()
                        .execute(() -> player.sendMessage(text))
                        .submit(this);
            } else {
                logger.error("An error occurred while resolving the path: " + path);
                e.printStackTrace();
            }

            return true;
        }

        return false;
    }

    /**
     * Checks, whether the player is holding a player head.
     *
     * @param player The player
     * @return The player head, if found and applicable
     */
    private Optional<ItemStack> checkHand(Player player) {
        Optional<ItemStack> itemStackOptional = player.getItemInHand(HandTypes.MAIN_HAND);

        if(!itemStackOptional.isPresent()
                || itemStackOptional.get().getItem() != ItemTypes.SKULL
                || itemStackOptional.get().get(Keys.SKULL_TYPE).orElse(SkullTypes.SKELETON) != SkullTypes.PLAYER) {
            player.sendMessage(
                    Text.of(TextColors.RED, "You are not holding a player head in your main hand.")
            );

            return Optional.empty();
        }

        return itemStackOptional;
    }

    /**
     * Gives the {@param player} the {@param itemStack}
     * or throws it on the ground near them if they have a full inventory.
     *
     * @param messageReceiver The {@link MessageReceiver} that receives
     *                        a notification about the items being thrown on the ground
     * @param player The {@link Player} to receive the {@param itemStack}
     * @param itemStack The {@link ItemStack} that is given.
     */
    public void give(MessageReceiver messageReceiver, Player player, ItemStack itemStack) {
        InventoryTransactionResult result = player.getInventory().offer(itemStack);
        Collection<ItemStackSnapshot> rejectedItems = result.getRejectedItems();

        if (rejectedItems.size() > 0) {
            Location<World> location = player.getLocation();
            World world = location.getExtent();
            PluginContainer pluginContainer = getPluginContainer();

            for(ItemStackSnapshot rejectedSnapshot : rejectedItems) {
                Item rejectedItem = (Item) world.createEntity(EntityTypes.ITEM, location.getPosition());

                rejectedItem.offer(Keys.REPRESENTED_ITEM, rejectedSnapshot);

                Cause.Builder cause = Cause.source(
                        EntitySpawnCause.builder()
                                .entity(rejectedItem)
                                .type(SpawnTypes.PLUGIN)
                                .build()
                        )
                        .owner(pluginContainer);

                if(messageReceiver != null)
                    cause.notifier(messageReceiver);

                world.spawnEntity(rejectedItem, cause.build());
            }

            if(messageReceiver != null)
                messageReceiver.sendMessage(Text.of(TextColors.YELLOW, "Some of the items could not be added to the inventory, so they have been thrown on the ground instead."));
        }
    }

    public MineskinService getMineskinService() {
        return Sponge.getServiceManager().provide(MineskinService.class)
                .orElseThrow(() -> new IllegalStateException("Could not access the Mineskin service."));
    }

    public PluginContainer getPluginContainer() {
        return Sponge.getPluginManager().fromInstance(this)
                .orElseThrow(() -> new IllegalStateException("Could not access the plugin container."));
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getConfigDir() {
        return configDir;
    }
}
