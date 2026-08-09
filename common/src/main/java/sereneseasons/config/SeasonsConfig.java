/*******************************************************************************
 * Copyright 2021, the Glitchfiend Team.
 * All rights reserved.
 ******************************************************************************/
package sereneseasons.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.InMemoryFormat;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import glitchcore.util.Environment;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.Level;
import sereneseasons.api.season.Season;
import sereneseasons.core.SereneSeasons;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.minecraft.server.level.ServerLevel.RAIN_DELAY;

public class SeasonsConfig extends glitchcore.config.Config
{
    // From ServerLevel
    private static final IntProvider THUNDER_DELAY = UniformInt.of(12000, 180000);

    // Marks a melt_speed that was absent from the config file, so the default for that sub season is used
    public static final float UNSET_MELT_SPEED = -1.0F;

    private static final Predicate<List<Config>> SEASON_PROPERTIES_VALIDATOR = (configs) -> configs.stream().allMatch(c -> SeasonProperties.decode(c).isPresent());

    // Melt speeds follow the seasonal lag of a real climate rather than the solstice: the yearly temperature
    // peak trails midsummer by weeks, so mid summer melts fastest and the curve is deliberately asymmetric.
    // Autumn outmelts the matching spring sub season, because spring still spends its heat on thawing snow
    // and frozen ground while autumn works on ground that is still warm.
    private static final Map<Season.SubSeason, SeasonProperties> DEFAULT_SEASON_PROPERTIES = Lists.newArrayList(
            new SeasonProperties(Season.SubSeason.EARLY_WINTER, 0.0F, 0, 0.0F, -0.8F, 12000, 36000, -1, -1),
            new SeasonProperties(Season.SubSeason.MID_WINTER, 0.0F, 0, 0.0F, -0.8F, 12000, 36000, -1, -1),
            new SeasonProperties(Season.SubSeason.LATE_WINTER, 0.0F, 0, 0.0F, -0.8F, 12000, 36000, -1, -1),
            new SeasonProperties(Season.SubSeason.EARLY_SPRING, 6.25F, 1, 12.0F, -0.25F, 12000, 96000, THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.MID_SPRING, 8.33F, 1, 25.0F, 0.0F, 12000, 96000, THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.LATE_SPRING, 12.5F, 1, 45.0F, 0.0F, 12000, 96000, THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.EARLY_SUMMER, 25.0F, 1, 75.0F, 0.0F, 12000, 96000, THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.MID_SUMMER, 25.0F, 1, 100.0F, 0.0F, 12000, 96000, THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.LATE_SUMMER, 25.0F, 1, 90.0F, 0.0F, 12000, 96000, THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.EARLY_AUTUMN, 12.5F, 1, 60.0F, 0.0F, RAIN_DELAY.minInclusive(), RAIN_DELAY.maxInclusive(), THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.MID_AUTUMN, 8.33F, 1, 30.0F, 0.0F, RAIN_DELAY.minInclusive(), RAIN_DELAY.maxInclusive(), THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive()),
            new SeasonProperties(Season.SubSeason.LATE_AUTUMN, 6.25F, 1, 15.0F, -0.25F, RAIN_DELAY.minInclusive(), RAIN_DELAY.maxInclusive(), THUNDER_DELAY.minInclusive(), THUNDER_DELAY.maxInclusive())
    ).stream().collect(Collectors.toMap(SeasonProperties::subSeason, v -> v));

    private static final Predicate<List<String>> RESOURCE_LOCATION_VALIDATOR = (list) ->
    {
        for (String s : list)
        {
            try
            {
                Identifier.parse(s);
            }
            catch (Exception e)
            {
                // Can't convert to a resource location, therefore this object is invalid
                return false;
            }
        }
        return true;
    };

    // Weather settings
    public boolean generateSnowAndIce;
    public boolean changeWeatherFrequency;

    // Time settings
    public int dayDuration;
    public int subSeasonDuration;
    public int startingSubSeason;
    public boolean progressSeasonWhileOffline;

    // Aesthetic settings
    public boolean changeGrassColor;
    public boolean changeFoliageColor;
    public boolean changeBirchColor;

    // Dimension settings
    public List<String> whitelistedDimensions;
    private static final List<String> defaultWhitelistedDimensions = Lists.newArrayList(Level.OVERWORLD.identifier().toString());

    // Snow melting settings
    private List<Config> seasonProperties;

    private Supplier<Map<Season.SubSeason, SeasonProperties>> seasonPropertiesMapper;


    public SeasonsConfig()
    {
        super(Environment.getConfigPath().resolve(SereneSeasons.MOD_ID + "/seasons.toml"));
    }

    @Override
    public void load()
    {
        generateSnowAndIce = add("weather_settings.generate_snow_ice", true, "Generate snow and ice during the Winter season");
        changeWeatherFrequency = add("weather_settings.change_weather_frequency", true, "Change the frequency of rain/snow/storms based on the season");

        dayDuration = addNumber("time_settings.day_duration", 24000, 20, Integer.MAX_VALUE, "The duration of a Minecraft day in ticks.\nThis only adjusts the internal length of a day used by the season cycle.\nIt is intended to be used in conjunction with another mod which adjusts the actual length of a Minecraft day.");
        subSeasonDuration = addNumber("time_settings.sub_season_duration", 8, 1, Integer.MAX_VALUE, "The duration of a sub season in days.");
        startingSubSeason = addNumber("time_settings.starting_sub_season", 1, 0, 12, "The starting sub season for new worlds.\n0 = Random, 1 - 3 = Early/Mid/Late Spring\n4 - 6 = Early/Mid/Late Summer\n7 - 9 = Early/Mid/Late Autumn\n10 - 12 = Early/Mid/Late Winter");
        progressSeasonWhileOffline = add("time_settings.progress_season_while_offline", true, "If the season should progress on a server with no players online");

        changeGrassColor = add("aesthetic_settings.change_grass_color", true, "Change the grass color based on the current season");
        changeFoliageColor = add("aesthetic_settings.change_foliage_color", true, "Change the foliage colour based on the current season");
        changeBirchColor = add("aesthetic_settings.change_birch_color", true, "Change the birch colour based on the current season");

        whitelistedDimensions = add("dimension_settings.whitelisted_dimensions", defaultWhitelistedDimensions, "Seasons will only apply to dimensons listed here", RESOURCE_LOCATION_VALIDATOR);

        final List<Config> defaultProperties = DEFAULT_SEASON_PROPERTIES.values().stream().map(SeasonProperties::encode).toList();
        seasonProperties = add("season_properties", defaultProperties, """
                melt_percent is the 0-1 percentage chance a snow or ice block will melt when chosen. (e.g. 100.0 = 100%, 50.0 = 50%)
                melt_rolls is the number of blocks randomly picked in each chunk, each tick. (High number rolls is not recommended on servers)
                melt_rolls should be 0 if blocks should not melt in that season.
                melt_speed is how fast snow and ice melt in that season, from 0.0 to 100.0.
                Set it to 0.0 for snow that never melts in that season. Snow never melts while it is
                actively snowing, whatever the season. The value is scaled down in colder biomes and
                scaled up while it is raining, so a season is a budget rather than a fixed rate.
                biome_temp_adjustment is the amount to adjust the biome temperature by from -10.0 to 10.0.
                min_rain_time is the minimum time interval between rain events in ticks. Set to -1 to disable rain.
                max_rain_time is the maximum time interval between rain events in ticks. Set to -1 to disable rain.
                min_thunder_time is the minimum time interval between thunder events in ticks. Set to -1 to disable thunder.
                max_thunder_time is the maximum time interval between thunder events in ticks. Set to -1 to disable thunder.""", SEASON_PROPERTIES_VALIDATOR);

        seasonPropertiesMapper = Suppliers.memoize(() -> {
            var map = new HashMap<>(DEFAULT_SEASON_PROPERTIES);
            seasonProperties.stream().map(SeasonProperties::decode).forEach(o -> o.ifPresent(v -> map.put(v.subSeason(), v.resolveMeltSpeed(DEFAULT_SEASON_PROPERTIES.get(v.subSeason())))));
            return map;
        });
    }

    public boolean isDimensionWhitelisted(ResourceKey<Level> dimension)
    {
        for (String whitelistedDimension : whitelistedDimensions)
        {
            if (dimension.identifier().toString().equals(whitelistedDimension))
            {
                return true;
            }
        }

        return false;
    }

    @Nullable
    public SeasonProperties getSeasonProperties(Season.SubSeason season)
    {
        return seasonPropertiesMapper.get().get(season);
    }

    public record SeasonProperties(Season.SubSeason subSeason, float meltChance, int meltRolls, float meltSpeed, float biomeTempAdjustment, int minRainTime, int maxRainTime, int minThunderTime, int maxThunderTime)
    {
        public Config encode()
        {
            Config config = Config.of(LinkedHashMap::new, InMemoryFormat.withUniversalSupport());
            config.add("season", this.subSeason.toString());
            config.add("melt_percent", this.meltChance);
            config.add("melt_rolls", this.meltRolls);
            config.add("melt_speed", this.meltSpeed);
            config.add("biome_temp_adjustment", this.biomeTempAdjustment);
            config.add("min_rain_time", this.minRainTime);
            config.add("max_rain_time", this.maxRainTime);
            config.add("min_thunder_time", this.minThunderTime);
            config.add("max_thunder_time", this.maxThunderTime);
            return config;
        }

        public static Optional<SeasonProperties> decode(Config config)
        {
            try
            {
                Season.SubSeason subSeason = config.getEnum("season", Season.SubSeason.class);
                float meltChance = config.<Number>get("melt_percent").floatValue();
                int rolls = config.getInt("melt_rolls");
                // Configs written before melt_speed existed omit it. UNSET_MELT_SPEED makes the mapper fall
                // back to the default for that sub season instead of discarding the whole entry.
                Number meltSpeedValue = config.get("melt_speed");
                float meltSpeed = meltSpeedValue == null ? UNSET_MELT_SPEED : meltSpeedValue.floatValue();
                float biomeTempAdjustment = config.<Number>get("biome_temp_adjustment").floatValue();
                int minRainTime = config.getInt("min_rain_time");
                int maxRainTime = config.getInt("max_rain_time");
                int minThunderTime = config.getInt("min_thunder_time");
                int maxThunderTime = config.getInt("max_thunder_time");

                Preconditions.checkArgument(meltChance >= 0.0F && meltChance <= 100.0F);
                Preconditions.checkArgument(rolls >= 0);
                Preconditions.checkArgument(meltSpeed == UNSET_MELT_SPEED || (meltSpeed >= 0.0F && meltSpeed <= 100.0F));
                Preconditions.checkArgument(biomeTempAdjustment >= -10.0 && biomeTempAdjustment <= 10.0);
                Preconditions.checkArgument(minRainTime <= maxRainTime);
                Preconditions.checkArgument(minThunderTime <= maxThunderTime);

                return Optional.of(new SeasonProperties(subSeason, meltChance, rolls, meltSpeed, biomeTempAdjustment, minRainTime, maxRainTime, minThunderTime, maxThunderTime));
            }
            catch (Exception e)
            {
                return Optional.empty();
            }
        }

        /**
         * Replaces an absent melt_speed with the one from {@code fallback}, so an older config file keeps
         * working. Returns this unchanged when melt_speed was present, or when there is no fallback.
         */
        public SeasonProperties resolveMeltSpeed(@Nullable SeasonProperties fallback)
        {
            if (this.meltSpeed != UNSET_MELT_SPEED || fallback == null)
            {
                return this;
            }

            return new SeasonProperties(this.subSeason, this.meltChance, this.meltRolls, fallback.meltSpeed(), this.biomeTempAdjustment, this.minRainTime, this.maxRainTime, this.minThunderTime, this.maxThunderTime);
        }

        public boolean canRain()
        {
            return this.minRainTime != -1 && this.maxRainTime != -1;
        }

        public boolean canThunder()
        {
            return this.minThunderTime != -1 && this.maxThunderTime != -1;
        }
    }

    static
    {
        Preconditions.checkState(DEFAULT_SEASON_PROPERTIES.keySet().containsAll(List.of(Season.SubSeason.values())));
    }
}