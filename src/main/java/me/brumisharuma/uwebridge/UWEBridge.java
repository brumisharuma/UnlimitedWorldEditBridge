package me.brumisharuma.uwebridge;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.function.BlockFunction;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.visitor.RegionVisitor;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.patterns.SingleBlockPattern;
import com.sk89q.worldedit.blocks.BaseBlock;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.Iterator;

public class UWEBridge extends JavaPlugin implements Listener {

    private WorldEditPlugin worldEdit;
    private FileConfiguration lang;
    private int blocksPerTick;
    private int batchDelay;

    @Override
    public void onEnable() {
        if (getResource("config.yml") != null) {
            saveDefaultConfig();
        }
        reloadConfig();
        blocksPerTick = getConfig().getInt("blocks-per-tick", 1000);
        batchDelay = getConfig().getInt("batch-delay", 1);
        
        loadLang();
        worldEdit = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
        Bukkit.getPluginManager().registerEvents(this, this);
        
        Bukkit.getConsoleSender().sendMessage(ChatColor.GOLD + "========================================");
        Bukkit.getConsoleSender().sendMessage(ChatColor.YELLOW + " UnlimitedWorldEditBridge " + ChatColor.GREEN + "v" + getDescription().getVersion());
        Bukkit.getConsoleSender().sendMessage(ChatColor.AQUA + " MADE BY BrumishAruma");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GOLD + "========================================");
    }

    private void loadLang() {
        File langFile = new File(getDataFolder(), "messages.yml");
        if (!langFile.exists()) {
            saveResource("messages.yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    private String getMsg(String path) {
        return ChatColor.translateAlternateColorCodes('&', lang.getString(path, path));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().toLowerCase();
        
        boolean isPaste = msg.startsWith("//paste");
        boolean isSet = msg.startsWith("//set ");
        boolean isWalls = msg.startsWith("//walls ");
        boolean isUndo = msg.equals("//undo");

        if (!isPaste && !isSet && !isWalls && !isUndo) return;

        BukkitPlayer actor = worldEdit.wrapPlayer(e.getPlayer());
        
        if (!actor.hasPermission("worldedit.*")) {
            if (isPaste && !actor.hasPermission("worldedit.clipboard.paste")) { e.getPlayer().sendMessage(getMsg("message-no-permission")); return; }
            if (isSet && !actor.hasPermission("worldedit.region.set")) { e.getPlayer().sendMessage(getMsg("message-no-permission")); return; }
            if (isWalls && !actor.hasPermission("worldedit.region.walls")) { e.getPlayer().sendMessage(getMsg("message-no-permission")); return; }
            if (isUndo && !actor.hasPermission("worldedit.history.undo")) { e.getPlayer().sendMessage(getMsg("message-no-permission")); return; }
        }

        e.setCancelled(true);

        try {
            LocalSession session = worldEdit.getSession(e.getPlayer());
            
            if (isUndo) {
                EditSession undone = session.undo(session.getBlockBag(actor), actor);
                if (undone != null) {
                    actor.print(getMsg("message-undo-success"));
                } else {
                    actor.print(getMsg("message-undo-none"));
                }
                return;
            }

            EditSession editSession = session.createEditSession(actor);
            editSession.setFastMode(true);
            
            if (isPaste) {
                ClipboardHolder holder = session.getClipboard();
                if (holder == null) {
                    actor.print(getMsg("message-no-clipboard"));
                    return;
                }
                
                org.bukkit.Location loc = e.getPlayer().getLocation();
                Vector pos = new Vector(loc.getX(), loc.getY(), loc.getZ());
                
                loadChunks(e.getPlayer().getWorld(), holder.getClipboard().getRegion(), pos);

                ForwardExtentCopy copy = holder.createPaste(editSession, editSession.getWorld().getWorldData())
                        .to(pos)
                        .ignoreAirBlocks(msg.contains("-a"))
                        .build();
                
                processOperation(actor, session, editSession, copy, "message-paste-success");
            }

            if (isSet || isWalls) {
                String input = msg.substring(isSet ? 6 : 8).trim();
                Region region = session.getSelection(actor.getWorld());
                
                Pattern pattern;
                if (input.equalsIgnoreCase("hand")) {
                    ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
                    if (hand == null || hand.getType() == Material.AIR) {
                        actor.print(getMsg("message-hand-empty"));
                        return;
                    }
                    pattern = new SingleBlockPattern(new BaseBlock(hand.getType().getId(), hand.getDurability()));
                } else {
                    ParserContext context = new ParserContext();
                    context.setActor(actor);
                    context.setWorld(actor.getWorld());
                    BaseBlock block = WorldEdit.getInstance().getBlockFactory().parseFromInput(input, context);
                    pattern = (Pattern) block;
                }
                
                if (isSet) {
                    processOperation(actor, session, editSession, new SetOperation(editSession, region, pattern), "message-set-success");
                } else {
                    processOperation(actor, session, editSession, new WallsOperation(editSession, region, pattern), "message-walls-success");
                }
            }

        } catch (Exception ex) {
            actor.print(getMsg("message-error").replace("%error%", ex.getMessage()));
            ex.printStackTrace();
        }
    }

    private void processOperation(BukkitPlayer actor, LocalSession session, EditSession editSession, Operation operation, String successMsgKey) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Operation currentOp = operation;
                    int processed = 0;
                    while (currentOp != null && processed < blocksPerTick) {
                        currentOp = currentOp.resume(new ParserContext());
                        processed++;
                    }
                    
                    if (currentOp != null) {
                        // Not finished, schedule next batch
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                processOperation(actor, session, editSession, operation, successMsgKey);
                            }
                        }.runTaskLater(UWEBridge.this, batchDelay);
                    } else {
                        // Finished
                        editSession.flushQueue();
                        session.remember(editSession);
                        actor.print(getMsg(successMsgKey));
                    }
                } catch (Exception e) {
                    actor.print(getMsg("message-error").replace("%error%", e.getMessage()));
                    e.printStackTrace();
                }
            }
        }.runTask(this);
    }

    private static class SetOperation implements Operation {
        private final EditSession editSession;
        private final Region region;
        private final Pattern pattern;
        private Iterator<Vector> iterator;

        public SetOperation(EditSession editSession, Region region, Pattern pattern) {
            this.editSession = editSession;
            this.region = region;
            this.pattern = pattern;
            this.iterator = region.iterator();
        }

        @Override
        public Operation resume(ParserContext context) {
            if (!iterator.hasNext()) return null;
            try {
                editSession.setBlock(iterator.next(), pattern);
            } catch (Exception e) {}
            return iterator.hasNext() ? this : null;
        }

        @Override
        public void cancel() {}
    }

    private static class WallsOperation implements Operation {
        private final EditSession editSession;
        private final Region region;
        private final Pattern pattern;
        private Iterator<Vector> iterator;

        public WallsOperation(EditSession editSession, Region region, Pattern pattern) {
            this.editSession = editSession;
            this.region = region;
            this.pattern = pattern;
            // Simplified walls logic for legacy iteration
            this.iterator = region.iterator();
        }

        @Override
        public Operation resume(ParserContext context) {
            if (!iterator.hasNext()) return null;
            Vector pos = iterator.next();
            // Only set if on the boundary of the region
            if (isWall(pos)) {
                try {
                    editSession.setBlock(pos, pattern);
                } catch (Exception e) {}
            }
            return iterator.hasNext() ? this : null;
        }

        private boolean isWall(Vector pos) {
            return pos.getBlockX() == region.getMinimumPoint().getBlockX() || pos.getBlockX() == region.getMaximumPoint().getBlockX() ||
                   pos.getBlockY() == region.getMinimumPoint().getBlockY() || pos.getBlockY() == region.getMaximumPoint().getBlockY() ||
                   pos.getBlockZ() == region.getMinimumPoint().getBlockZ() || pos.getBlockZ() == region.getMaximumPoint().getBlockZ();
        }

        @Override
        public void cancel() {}
    }

    private void loadChunks(World world, Region region, Vector at) {
        Vector min = region.getMinimumPoint().add(at);
        Vector max = region.getMaximumPoint().add(at);

        for (int x = min.getBlockX() >> 4; x <= max.getBlockX() >> 4; x++) {
            for (int z = min.getBlockZ() >> 4; z <= max.getBlockZ() >> 4; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    world.loadChunk(x, z, true);
                }
            }
        }
    }
}
