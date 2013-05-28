package com.mikeprimm.bukkit.SnowDrift;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class SnowDrift extends JavaPlugin {
    public Logger log;

    public ConfigRec[] configByBiome;
    public int tick_period;
    public Map<String, WorldProcessingRec> worlds = new HashMap<String, WorldProcessingRec>();
    public World.Environment[] worldenv;
    private boolean firing_snow_form;
    public int chunksPerTick;
    public int blocksPerChunk;
    public int minTickInterval;
    public final int snowID = Material.SNOW.getId();
    public final BlockFace[] driftDirectionStraight;
    public final BlockFace[] driftDirectionLeft;
    public final BlockFace[] driftDirectionRight;
    public BitSet blockSnowForm = new BitSet();

    public SnowDrift() {
        /* Build drift direction table */
        driftDirectionStraight = new BlockFace[BlockFace.values().length];
        driftDirectionLeft = new BlockFace[BlockFace.values().length];
        driftDirectionRight = new BlockFace[BlockFace.values().length];

        driftDirectionStraight[BlockFace.NORTH.ordinal()] = BlockFace.SOUTH;
        driftDirectionLeft[BlockFace.NORTH.ordinal()] = BlockFace.SOUTH_EAST;
        driftDirectionRight[BlockFace.NORTH.ordinal()] = BlockFace.SOUTH_WEST;
        
        driftDirectionStraight[BlockFace.SOUTH.ordinal()] = BlockFace.NORTH;
        driftDirectionLeft[BlockFace.SOUTH.ordinal()] = BlockFace.NORTH_WEST;
        driftDirectionRight[BlockFace.SOUTH.ordinal()] = BlockFace.NORTH_EAST;
        
        driftDirectionStraight[BlockFace.WEST.ordinal()] = BlockFace.EAST;
        driftDirectionLeft[BlockFace.WEST.ordinal()] = BlockFace.NORTH_EAST;
        driftDirectionRight[BlockFace.WEST.ordinal()] = BlockFace.SOUTH_EAST;

        driftDirectionStraight[BlockFace.EAST.ordinal()] = BlockFace.WEST;
        driftDirectionLeft[BlockFace.EAST.ordinal()] = BlockFace.SOUTH_WEST;
        driftDirectionRight[BlockFace.EAST.ordinal()] = BlockFace.NORTH_WEST;
        
        driftDirectionStraight[BlockFace.NORTH_EAST.ordinal()] = BlockFace.SOUTH_WEST;
        driftDirectionLeft[BlockFace.NORTH_EAST.ordinal()] = BlockFace.SOUTH;
        driftDirectionRight[BlockFace.NORTH_EAST.ordinal()] = BlockFace.WEST;

        driftDirectionStraight[BlockFace.SOUTH_EAST.ordinal()] = BlockFace.NORTH_WEST;
        driftDirectionLeft[BlockFace.SOUTH_EAST.ordinal()] = BlockFace.WEST;
        driftDirectionRight[BlockFace.SOUTH_EAST.ordinal()] = BlockFace.NORTH;

        driftDirectionStraight[BlockFace.NORTH_WEST.ordinal()] = BlockFace.SOUTH_EAST;
        driftDirectionLeft[BlockFace.NORTH_WEST.ordinal()] = BlockFace.EAST;
        driftDirectionRight[BlockFace.NORTH_WEST.ordinal()] = BlockFace.SOUTH;

        driftDirectionStraight[BlockFace.SOUTH_WEST.ordinal()] = BlockFace.NORTH_EAST;
        driftDirectionLeft[BlockFace.SOUTH_WEST.ordinal()] = BlockFace.NORTH;
        driftDirectionRight[BlockFace.SOUTH_WEST.ordinal()] = BlockFace.EAST;
    }
    
    /* On disable, stop doing our function */
    public void onDisable() {
        
    }

    public void onEnable() {
        log = this.getLogger();
        
        log.info("SnowDrift loaded");
        
        final FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
    
        // Load biome specific settings
        Biome[] v = Biome.values();
        configByBiome = new ConfigRec[v.length];
        for (Biome b : v) {
            ConfigurationSection cs = cfg.getConfigurationSection("biome." + b.name());
            if (cs != null) {
                configByBiome[b.ordinal()] = new ConfigRec(cs);
            }
        }
        /* Get tick period */
        tick_period = cfg.getInt("general.tick-period", 1);
        if(tick_period < 1) tick_period = 1;
        /* Get processing rate defaults */
        chunksPerTick = cfg.getInt("general.chunks-per-tick", 1);
        if (chunksPerTick < 1) chunksPerTick = 1;
        blocksPerChunk = cfg.getInt("general.blocks-per-chunk", 1);
        if (blocksPerChunk < 1) blocksPerChunk = 1;
        minTickInterval = cfg.getInt("general.min-tick-interval", 1);
        if (minTickInterval < 1) minTickInterval = 1;
        /* Get world environment limits */
        List<String> env = cfg.getStringList("general.world-env");
        if (env == null) {
            worldenv = new World.Environment[] { World.Environment.NORMAL };
        }
        else {
            worldenv = new World.Environment[env.size()];
            for (int i = 0; i < env.size(); i++) {
                worldenv[i] = World.Environment.valueOf(env.get(i));
            }
        }
        List<Integer> blk = cfg.getIntegerList("general.block-snow-form");
        if (blk != null) {
            blockSnowForm.clear();
            for (Integer in : blk) {
                blockSnowForm.set(in);
            }
        }
        /* Initialize loaded worlds */
        for (World w : this.getServer().getWorlds()) {
            if (isProcessedWorld(w)) {
                worlds.put(w.getName(), new WorldProcessingRec(SnowDrift.this, cfg.getConfigurationSection("worlds." + w.getName()), w));
            }
        }
        /* Add listener for world events */
        Listener pl = new Listener() {
            @EventHandler(priority=EventPriority.MONITOR)
            public void onWorldLoad(WorldLoadEvent evt) {
                World w = evt.getWorld();
                if (isProcessedWorld(w)) {
                    worlds.put(w.getName(), new WorldProcessingRec(SnowDrift.this, cfg.getConfigurationSection("worlds." + w.getName()), w));
                }
            }
            @EventHandler(priority=EventPriority.MONITOR)
            public void onWorldUnload(WorldUnloadEvent evt) {
                if (evt.isCancelled()) return;
                worlds.remove(evt.getWorld().getName());
            }
            @EventHandler(priority=EventPriority.MONITOR)
            public void onBlockForm(BlockFormEvent evt) {
                if (evt.isCancelled()) return;
                if (firing_snow_form) return;
                BlockState bs = evt.getNewState();
                if (bs.getTypeId() == snowID) { // If snow form
                    World w = bs.getWorld();
                    WorldProcessingRec rec = worlds.get(w.getName());
                    if (rec != null) {
                        rec.processBlockForm(SnowDrift.this, bs);
                    }
                }
            }
        };
        getServer().getPluginManager().registerEvents(pl, this);
        
        this.getServer().getScheduler().scheduleSyncRepeatingTask(this, 
                new Runnable() {
                    public void run() {
                        processTick();
                    }
                },
                tick_period, tick_period);
        
        if (blockSnowForm.isEmpty() == false) {
            /* Add listener for form events */
            Listener bfl = new Listener() {
                @EventHandler(priority=EventPriority.NORMAL)
                public void onBlockForm(BlockFormEvent evt) {
                    if (evt.isCancelled()) return;
                    BlockState bs = evt.getNewState();
                    if (bs.getTypeId() == snowID) { // If snow form
                        Block b = evt.getBlock().getRelative(BlockFace.DOWN);
                        if ((b != null) && (blockSnowForm.get(b.getTypeId()))) {
                            evt.setCancelled(true);
                        }
                    }
                }
            };
            getServer().getPluginManager().registerEvents(bfl, this);

        }
    }
    
    private void processTick() {
        for (WorldProcessingRec rec : worlds.values()) {
            rec.processTick(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        return false;
    }
    
    private boolean isProcessedWorld(World w) {
        World.Environment we = w.getEnvironment();
        for (int i = 0; i < worldenv.length; i++) {
            if (we == worldenv[i]) {
                return true;
            }
        }
        return false;
    }
    
    public boolean doSnowForm(World w, int x, int y, int z, int depth) {
        Block blk = w.getBlockAt(x, y, z);
        BlockState blockState = blk.getState();
        blockState.setTypeId(snowID);
        if (depth < 1) depth = 1;
        if (depth > 8) depth = 8;
        blockState.setRawData((byte)((depth - 1) & 0x7));
        BlockFormEvent event = new BlockFormEvent(blk, blockState);
        firing_snow_form = true;
        Bukkit.getPluginManager().callEvent(event);
        firing_snow_form = false;
        if (!event.isCancelled()) {
            blockState.update(true);
            return true;
        }
        return false;
    }
    public boolean doSnowFade(World w, int x, int y, int z) {
        Block blk = w.getBlockAt(x, y, z);
        BlockState state = blk.getState();
        state.setTypeId(0);
        state.setRawData((byte)0);
        BlockFadeEvent event = new BlockFadeEvent(blk, state);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            state.update(true);
            return true;
        }
        return false;
    }
    public ConfigRec getConfigForCoord(World w, int x, int z) {
        Biome b = w.getBiome(x,  z);    // Get biome
        if (b == null) return null;
        return configByBiome[b.ordinal()];
    }
}
