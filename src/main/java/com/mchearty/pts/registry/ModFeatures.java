package com.mchearty.pts.registry;

import com.mchearty.pts.PtsMod;
import com.mchearty.pts.worldgen.TerrainSmoothingFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Deferred registration holder for PTS worldgen features.
 */
public class ModFeatures {

  public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, PtsMod.MODID);

  /** The terrain-smoothing feature used in all dimensions. */
  public static final Supplier<Feature<NoneFeatureConfiguration>> SMOOTHING_FEATURE = FEATURES.register("terrain_smoothing",
      () -> new TerrainSmoothingFeature(NoneFeatureConfiguration.CODEC));
}