package com.mikeprimm.bukkit.SnowDrift;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

public class ConfigRec {
    public float meltSnowBySky[];
    public boolean checkNewForMelt;
    public boolean checkNewForDrift;
    public BlockFace windDirection;
    public float driftUpwindBlock;
    public float driftDownhill;
    public float driftUphill;
    public float driftAccumulateDown[];
    public float driftAccumulateUp[];
    public float diagonalDrift;
    
    public ConfigRec(ConfigurationSection cs) {
        meltSnowBySky = new float[16];
        for (int i = 0; i < 16; i++) {
            meltSnowBySky[i] = (float)cs.getDouble("melt-snow-by-sky-light-" + i, 0.0);
        }
        checkNewForMelt = cs.getBoolean("check-new-snow-for-melt", false);
        checkNewForDrift = cs.getBoolean("check-new-snow-for-drift", false);
        String dir = cs.getString("wind-direction", "NORTH_WEST");
        windDirection = BlockFace.valueOf(dir.toUpperCase());
        if (windDirection == null) windDirection = BlockFace.NORTH_WEST;
        driftUpwindBlock = (float)cs.getDouble("drift-upwind-block", 0.0);
        driftDownhill = (float)cs.getDouble("drift-downhill", 0.0);
        driftUphill = (float)cs.getDouble("drift-uphill", 0.0);
        driftAccumulateDown = new float[9];
        driftAccumulateUp = new float[9];
        float dd = 0.0F;
        float du = 0.0F;
        for (int i = 0; i < 9; i++) {
            driftAccumulateDown[i] = (float)cs.getDouble("drift-accumulate-" + i + "-less", dd);
            dd = driftAccumulateDown[i];
            driftAccumulateUp[i] = (float)cs.getDouble("drift-accumulate-" + i + "-more", du);
            du = driftAccumulateUp[i];
        }
        diagonalDrift = (float)cs.getDouble("diagonal-drift", 0.0);
    }
    public BlockFace getDriftDir(SnowDrift drift, float rnd) {
        if ((100.0*rnd) < diagonalDrift) {
            if ((50.0*rnd) < diagonalDrift) {
                return drift.driftDirectionLeft[windDirection.ordinal()];
            }
            return drift.driftDirectionRight[windDirection.ordinal()];
        }
        return drift.driftDirectionStraight[windDirection.ordinal()];
    }
}
