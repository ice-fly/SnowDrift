package com.mikeprimm.bukkit.SnowDrift;

import java.util.ArrayList;
import java.util.Random;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;

public class WorldProcessingRec {
    private World world;
    private ArrayList<ChunkCoord> chunksToDo = new ArrayList<ChunkCoord>();
    private int index = 0;
    private int chunksPerTick;
    private int blocksPerChunk;
    private int minTickInterval;
    private int intervalCnt;
    private int loadedNeighbors;    // 2 bits per neighbor at 2*((xoff+1)*3 + (zoff+1)) : 01=unchecked, 01=loaded, 10=unloaded
    
    private static final int BIGPRIME = 15485867;
    private Random rnd = new Random();

    private static class ChunkCoord {
        int x, z;
    }
    
    public WorldProcessingRec(SnowDrift drift, ConfigurationSection cs, World w) {
        world = w;
        if (cs != null) {
            chunksPerTick = cs.getInt("chunks-per-tick", drift.chunksPerTick);
            blocksPerChunk = cs.getInt("blocks-per-chunk", drift.blocksPerChunk);
            minTickInterval = cs.getInt("min-tick-interval", drift.minTickInterval);
        }
        else {
            chunksPerTick = drift.chunksPerTick;
            blocksPerChunk = drift.blocksPerChunk;
            minTickInterval = drift.minTickInterval;
        }
    }
    
    public void processBlockForm(SnowDrift sd, BlockState bs) {
    }
    
    public void processTick(SnowDrift drift) {
        intervalCnt--;
        for (int i = 0; i < chunksPerTick; i++) {
            if (index == 0) {   /* No chunks queued */
                if (intervalCnt > 0) { // Too soon for traversing same chunk set
                    return;
                }
                intervalCnt = minTickInterval; // reset interval
                Chunk[] chunks = world.getLoadedChunks();    /* Get list of loaded chunks */
                int cnt = chunks.length;
                if (cnt == 0) {
                    return;
                }
                chunksToDo.clear();
                int ord = BIGPRIME % cnt;
                for (int j = 0; j < chunks.length; j++) {
                    if (chunks[ord] != null) {
                        ChunkCoord cc = new ChunkCoord();
                        cc.x = chunks[ord].getX();
                        cc.z = chunks[ord].getZ();
                        chunksToDo.add(cc);
                        chunks[ord] = null;
                    }
                    else {
                        drift.log.info("Did skip");
                    }
                    ord = (ord + BIGPRIME) % cnt;
                }
                index = chunksToDo.size();
            }
            // Get next chunk coord
            index--;
            ChunkCoord coord = chunksToDo.get(index);
            // Get chunk to tick
            if (!world.isChunkLoaded(coord.x,  coord.z)) {
                continue;
            }
            tickChunk(drift, coord.x << 4, coord.z << 4);
        }
    }
    private void tickChunk(SnowDrift drift, int x0, int z0) {
        loadedNeighbors = 0; // Reset loaded neightbox chunk cache
        for (int i = 0; i < blocksPerChunk; i++) {
            int x = rnd.nextInt(16);
            int z = rnd.nextInt(16);
            ConfigRec cr = drift.getConfigForCoord(world, x0+x, z0+z);
            if (cr == null) continue;
            int y = world.getHighestBlockYAt(x0+x, z0+z);
            int type = world.getBlockTypeIdAt(x0+x, y, z0+z);
            if (type != drift.snowID) { // Top isn't snow? try one random spot below
                if (y > 0) {
                    y = rnd.nextInt(y);
                    type = world.getBlockTypeIdAt(x0+x, y, z0+z);
                }
            }
            if (type == drift.snowID) { // Found snow - process it
                Block b = world.getBlockAt(x0+x, y, z0+z);
                processSnow(drift, cr, x0+x, y, z0+z, b);
            }
        }
    }
    private void processSnow(SnowDrift drift, ConfigRec cr, int x, int y, int z, Block b) {
        // Check for melting
        float prob = cr.meltSnowBySky[b.getLightFromSky()];
        if ((prob > 0.0) && ((100.0 * rnd.nextFloat()) < prob)) {
            drift.doSnowFade(world, x, y, z);
            //drift.log.info("processSnow(" + world.getName() + "," + x + "," + y + "," + z + ") - melted");
            return;
        }
        int h0 = b.getData() + 1;   // Get snow height
        // Get potential drift direction
        BlockFace driftdir = cr.getDriftDir(drift, rnd.nextFloat());
        // See if chunk we're drifting to is loaded - quit if no
        int dx = x + driftdir.getModX();
        int dz = z + driftdir.getModZ();
        if (checkNeighborLoaded(x, z, dx, dz) == false) {
            return;
        }
        // If upwind block chance
        if (cr.driftUpwindBlock > 0.0) {
            // See if upwind is loaded too
            int upx = x - driftdir.getModX();
            int upz = z - driftdir.getModZ();
            if (checkNeighborLoaded(x, z, upx, upz) == false) {
                return;
            }
            Block upblk = b.getRelative(driftdir.getOppositeFace());
            if (upblk == null) return;
            Material m = upblk.getType();
            // If snow, and higher than us
            if (((m == Material.SNOW) && ((upblk.getData() + 1) > h0))) {
                // See if blocked drift
                if ((100.0 * rnd.nextFloat()) < cr.driftUpwindBlock) {
                    return;
                }
            }
            else if (m.isSolid()) {
                // See if blocked drift
                if ((100.0 * rnd.nextFloat()) < cr.driftUpwindBlock) {
                    return;
                }
            }
        }
        // Check for block in direction
        Block nxtblk = b.getRelative(driftdir);
        if (nxtblk == null) return;
        // If blocked, cannot drift
        int nxttype = nxtblk.getTypeId();
        if ((nxttype != 0) && (nxttype != drift.snowID)) {  // Not air and not snow
            // See if uphill possible
            if (cr.driftUphill > 0.0) {
                if (canPlaceSnowOn(drift, nxtblk) == false) {  // Bad destination?
                    return;
                }
                nxtblk = nxtblk.getRelative(BlockFace.UP); // Look up
                if (nxtblk == null) return;
                nxttype = nxtblk.getTypeId();
                if (nxttype != 0) {  // Not air
                    return; // No drift
                }
                if ((100.0 * rnd.nextFloat()) >= cr.driftDownhill) {
                    return; // No drift
                }
            }
            else {
                return; // No drift
            }
        }
        else if (nxttype == drift.snowID) { // Accumulation drift?
            int h1 = nxtblk.getData() + 1;  // Get snow heights
            prob = 0.0F;
            if (h0 >= h1) {  // Source higher than destination 
                prob = cr.driftAccumulateDown[h0 - h1];
            }
            else {
                prob = cr.driftAccumulateUp[h1 - h0];
            }
            if ((prob > 0.0) && ((100.0 * rnd.nextFloat()) < prob)) { // If drifting?
                // Drift out - quit if cancelled
                if (!driftOut(drift, b, h0)) {
                    return; // If cancelled, no drift
                }
            }
            // Fall through : nxtblk is destination
        }
        else {  // Else, was air - find block to land on
            boolean dropped = false;
            do {
                Block below = nxtblk.getRelative(BlockFace.DOWN);
                if (below == null) return;
                nxttype = below.getTypeId();
                if ((nxttype == 0) || (nxttype == drift.snowID)) {
                    nxtblk = below;
                    dropped = true;
                }
                // If landed on block we can't be placed on
                else if (canPlaceSnowOn(drift, below) == false) {
                    return;
                }
            } while (nxttype == 0);
            // If dropped, see if it happened
            if (dropped) {
                if ((cr.driftDownhill > 0.0) && ((100.0 * rnd.nextFloat()) < cr.driftDownhill)) {
                    // Drift out - quit if cancelled
                    if (!driftOut(drift, b, b.getData() + 1)) {
                        return; // If cancelled, no drift
                    }
                }
                else { // No drift
                    return;
                }
            }
            else {  // Else, drifting sideways
                prob = cr.driftAccumulateDown[h0];
                if ((prob > 0.0) && ((100.0 * rnd.nextFloat()) < prob)) { // If drifting?
                    // Drift out - quit if cancelled
                    if (!driftOut(drift, b, h0)) {
                        return; // If cancelled, no drift
                    }
                }
                // Fall through : nxtblk is destination
            }
        }
        //drift.log.info("processSnow(" + world.getName() + "," + x + "," + y + "," + z + ") - drift out");
        // If we're here, we've drifted out and nxtblk is the destination for the drift in
        cr = drift.getConfigForCoord(world, nxtblk.getX(), nxtblk.getZ()); // Check config for destination
        if (cr == null) {   // Nothing?  Bad destination
            //drift.log.info("processSnow(" + world.getName() + "," + x + "," + y + "," + z + ") - no drift in");
            return;
        }
        if (nxtblk.getTypeId() == drift.snowID) {   // If adding to existing snow
            byte dat = nxtblk.getData();
            if (dat < 7) {
                nxtblk.setData((byte)(dat+ 1)); // Just add it
            }
        }
        else {  // Else, new snow block
            drift.doSnowForm(world, nxtblk.getX(), nxtblk.getY(), nxtblk.getZ(), 1);
        }
        //drift.log.info("processSnow(" + world.getName() + "," + nxtblk.getX() + "," + nxtblk.getY() + "," + nxtblk.getZ() + ") - drift in");
    }
    private boolean driftOut(SnowDrift drift, Block b, int prevheight) {
        if (prevheight <= 1) {
            if (!drift.doSnowFade(world, b.getX(), b.getY(), b.getZ())) {
                return false;
            }
        }
        else {  // Else, reduce snow
            b.setData((byte)prevheight);
        }
        return true;
    }
    private boolean canPlaceSnowOn(SnowDrift drift, Block b) {
        Material m = b.getType();
        if ((m == Material.LEAVES) || (m.isSolid() && m.isOccluding())) {
            if (drift.blockSnowForm.get(m.getId()))
                return false;
            else
                return true;
        }
        else
            return false;
    }
    private boolean checkNeighborLoaded(int x, int z, int xoff, int zoff) {
        int cx = ((x+xoff) >> 4);
        int cz = ((z+zoff) >> 4);
        x = x >> 4;
        z = z >> 4;
        if ((x == cx) && (z == cz)) return true;
        int off = 3*(cx - x + 1) + (cz - z + 1);
        int v = (loadedNeighbors >> (2*off)) & 0x3;
        if (v == 0) {   // Untested
            if (world.isChunkLoaded(cx, cz)) {
                v = 1;
            }
            else {
                v = 2;
            }
            loadedNeighbors |= v << (off*2);
        }
        return (v == 1);
    }
}
