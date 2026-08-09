/*******************************************************************************
 * Copyright 2021, the Glitchfiend Team.
 * All rights reserved.
 ******************************************************************************/
package sereneseasons.season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.config.SeasonsConfig;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModTags;

public class SeasonHooks
{
    // Temperature scaling applied to a season's melt speed. Biome temperatures run from -0.5 to 2.0, so
    // adding 0.5 turns the coldest biome into the floor and leaves 0.5 (a plains-like biome) near 1.0.
    private static final float MIN_TEMPERATURE_MELT_FACTOR = 0.05F;
    private static final float MAX_TEMPERATURE_MELT_FACTOR = 2.0F;

    // Melting is faster while rain is falling on the snow.
    private static final float RAIN_MELT_FACTOR = 1.75F;

    //
    // Hooks called by ASM
    //

    public static boolean shouldSnowHook(Biome biome, LevelReader levelReader, BlockPos pos, int seaLevel)
    {
        if ((ModConfig.seasons.generateSnowAndIce && warmEnoughToRainSeasonal(levelReader, pos, seaLevel)) || (!ModConfig.seasons.generateSnowAndIce && biome.warmEnoughToRain(pos, seaLevel)))
        {
            return false;
        }
        else
        {
            if (levelReader.isInsideBuildHeight(pos.getY()) && levelReader.getBrightness(LightLayer.BLOCK, pos) < 10)
            {
                BlockState blockstate = levelReader.getBlockState(pos);
                if (blockstate.isAir() && Blocks.SNOW.defaultBlockState().canSurvive(levelReader, pos))
                {
                    return true;
                }
            }

            return false;
        }
    }

    public static boolean shouldFreezeWarmEnoughToRainHook(Biome biome, BlockPos pos, int seaLevel, LevelReader levelReader)
    {
        return (ModConfig.seasons.generateSnowAndIce && warmEnoughToRainSeasonal(levelReader, pos, seaLevel)) || (!ModConfig.seasons.generateSnowAndIce && biome.warmEnoughToRain(pos, seaLevel));
    }

    public static boolean isRainingAtHook(Level level, BlockPos position)
    {
        if (!level.isRaining()) return false;
        else if (!level.canSeeSky(position)) return false;
        else if (level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, position).getY() > position.getY()) return false;
        else
        {
            Holder<Biome> biome = level.getBiome(position);

            if (ModConfig.seasons.isDimensionWhitelisted(level.dimension()) && !biome.is(ModTags.Biomes.BLACKLISTED_BIOMES))
            {
                return getPrecipitationAtSeasonal(level, biome, position, level.getSeaLevel()) == Biome.Precipitation.RAIN && warmEnoughToRainSeasonal(level, biome, position, level.getSeaLevel());
            }
            else
            {
                return biome.value().getPrecipitationAt(position, level.getSeaLevel()) == Biome.Precipitation.RAIN && biome.value().getTemperature(position, level.getSeaLevel()) >= 0.15F;
            }
        }
    }

    //
    // Hooks for different calls to getPrecipitationAt in Biome
    //

    public static Biome.Precipitation getPrecipitationAtTickIceAndSnowHook(LevelReader level, Biome biome, BlockPos pos, int seaLevel)
    {
        if (!biome.hasPrecipitation())
        {
            return Biome.Precipitation.NONE;
        }
        else
        {
            boolean shouldSnow = (ModConfig.seasons.generateSnowAndIce && coldEnoughToSnowSeasonal(level, pos, seaLevel)) || (!ModConfig.seasons.generateSnowAndIce && biome.coldEnoughToSnow(pos, seaLevel));
            return shouldSnow ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
        }
    }

    //
    // Melting
    //

    /**
     * Whether snow is actually falling on {@code pos} right now, as opposed to the season merely being cold
     * enough for snow. Requires active weather, an open sky and the position to be at or above the surface.
     */
    public static boolean isSnowingAt(Level level, BlockPos pos)
    {
        if (!level.isRaining() || !level.canSeeSky(pos))
        {
            return false;
        }

        if (level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY())
        {
            return false;
        }

        Holder<Biome> biome = level.getBiome(pos);

        if (ModConfig.seasons.isDimensionWhitelisted(level.dimension()) && !biome.is(ModTags.Biomes.BLACKLISTED_BIOMES))
        {
            return getPrecipitationAtSeasonal(level, biome, pos, level.getSeaLevel()) == Biome.Precipitation.SNOW;
        }

        return biome.value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.SNOW;
    }

    /**
     * How fast snow and ice should melt at {@code pos}, as a 0-1 multiplier to apply to whatever melting
     * mechanism the caller owns. Zero means nothing may melt here at all.
     * <p>
     * The season sets the budget through its melt_speed. On top of that, colder biomes melt more slowly than
     * warmer ones in the same season, and rain falling on the position speeds melting up. Melting is blocked
     * outright during winter and, in every season, while snow is actively falling — a biome cold enough to
     * snow in midsummer keeps its snow for as long as the snowfall lasts.
     */
    public static float getMeltSpeed(Level level, Holder<Biome> biome, BlockPos pos)
    {
        if (!ModConfig.seasons.generateSnowAndIce || !ModConfig.seasons.isDimensionWhitelisted(level.dimension()) || biome.is(ModTags.Biomes.BLACKLISTED_BIOMES))
        {
            return 0.0F;
        }

        Season.SubSeason subSeason = SeasonHelper.getSeasonState(level).getSubSeason();

        if (subSeason.getSeason() == Season.WINTER)
        {
            return 0.0F;
        }

        SeasonsConfig.SeasonProperties seasonProperties = ModConfig.seasons.getSeasonProperties(subSeason);

        if (seasonProperties == null || seasonProperties.meltSpeed() <= 0.0F)
        {
            return 0.0F;
        }

        if (isSnowingAt(level, pos))
        {
            return 0.0F;
        }

        float speed = seasonProperties.meltSpeed() / 100.0F;

        // A colder biome holds its snow longer than a warmer one under the same season.
        float temperature = getBiomeTemperature(level, biome, pos, level.getSeaLevel());
        speed *= Mth.clamp(temperature + 0.5F, MIN_TEMPERATURE_MELT_FACTOR, MAX_TEMPERATURE_MELT_FACTOR);

        // Rain falling on snow melts it faster than dry weather does.
        if (level.isRainingAt(pos))
        {
            speed *= RAIN_MELT_FACTOR;
        }

        return Mth.clamp(speed, 0.0F, 1.0F);
    }

    public static float getMeltSpeed(Level level, BlockPos pos)
    {
        return getMeltSpeed(level, level.getBiome(pos), pos);
    }

    //
    // General utilities
    //
    public static boolean coldEnoughToSnowSeasonal(LevelReader level, BlockPos pos, int seaLevel)
    {
        return coldEnoughToSnowSeasonal(level, level.getBiome(pos), pos, seaLevel);
    }

    public static boolean coldEnoughToSnowSeasonal(LevelReader level, Holder<Biome> biome, BlockPos pos, int seaLevel)
    {
        return !warmEnoughToRainSeasonal(level, biome, pos, seaLevel);
    }

    public static boolean warmEnoughToRainSeasonal(LevelReader level, BlockPos pos, int seaLevel)
    {
        return warmEnoughToRainSeasonal(level, level.getBiome(pos), pos, seaLevel);
    }

    public static boolean warmEnoughToRainSeasonal(LevelReader level, Holder<Biome> biome, BlockPos pos, int seaLevel)
    {
        return getBiomeTemperature(level, biome, pos, seaLevel) >= 0.15F;
    }

    public static float getBiomeTemperature(LevelReader level, Holder<Biome> biome, BlockPos pos, int seaLevel)
    {
        if (!(level instanceof Level))
        {
            return biome.value().getTemperature(pos, seaLevel);
        }

        return getBiomeTemperature((Level)level, biome, pos, seaLevel);
    }

    public static float getBiomeTemperature(Level level, Holder<Biome> biome, BlockPos pos, int seaLevel)
    {
        if (!ModConfig.seasons.isDimensionWhitelisted(level.dimension()) || biome.is(ModTags.Biomes.BLACKLISTED_BIOMES))
        {
            return biome.value().getTemperature(pos, seaLevel);
        }

        return getBiomeTemperatureInSeason(new SeasonTime(SeasonHelper.getSeasonState(level).getSeasonCycleTicks()).getSubSeason(), biome, pos, seaLevel);
    }

    public static float getBiomeTemperatureInSeason(Season.SubSeason subSeason, Holder<Biome> biome, BlockPos pos, int seaLevel)
    {
        boolean tropicalBiome = biome.is(ModTags.Biomes.TROPICAL_BIOMES);
        float biomeTemp = biome.value().getTemperature(pos, seaLevel);
        if (!tropicalBiome && biome.value().getBaseTemperature() <= 0.8F && !biome.is(ModTags.Biomes.BLACKLISTED_BIOMES))
        {
            biomeTemp = Mth.clamp(biomeTemp + ModConfig.seasons.getSeasonProperties(subSeason).biomeTempAdjustment(), -0.5F, 2.0F);
        }

        return biomeTemp;
    }

    public static boolean hasPrecipitationSeasonal(Level level, Holder<Biome> biome)
    {
        if (biome.is(ModTags.Biomes.TROPICAL_BIOMES))
        {
            Season.TropicalSeason tropicalSeason = SeasonHelper.getSeasonState(level).getTropicalSeason();

            switch (tropicalSeason)
            {
                case MID_DRY:
                    return false;

                case MID_WET:
                    return true;

                default:
                    break;
            }
        }

        return biome.value().hasPrecipitation();
    }

    public static Biome.Precipitation getPrecipitationAtSeasonal(Level level, Holder<Biome> biome, BlockPos pos, int seaLevel)
    {
        if (!hasPrecipitationSeasonal(level, biome))
        {
            return Biome.Precipitation.NONE;
        }
        else
        {
            return coldEnoughToSnowSeasonal(level, biome, pos, seaLevel) ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
        }
    }
}
