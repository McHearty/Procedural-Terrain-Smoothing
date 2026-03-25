package com.mchearty.pts.datagen;

import com.mchearty.pts.PtsMod;
import com.mchearty.pts.registry.ModFeatures;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.BiomeModifiers;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Provides built-in datapack entries for PTS world generation.
 *
 * <p>Registers a {@link ConfiguredFeature}, {@link PlacedFeature}, and biome
 * modifiers that add the terrain-smoothing feature to the Overworld, Nether,
 * and End in the {@code UNDERGROUND_DECORATION} step.
 */
public class PtsWorldGenProvider extends DatapackBuiltinEntriesProvider {

  private static final ResourceKey<ConfiguredFeature<?, ?>> CF_KEY = ResourceKey.create(
      Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(PtsMod.MODID, "terrain_smoothing_cf")
  );
  private static final ResourceKey<PlacedFeature> PF_KEY = ResourceKey.create(
      Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(PtsMod.MODID, "terrain_smoothing_pf")
  );

  private static final ResourceKey<BiomeModifier> BM_OVERWORLD = ResourceKey.create(
      NeoForgeRegistries.Keys.BIOME_MODIFIERS, ResourceLocation.fromNamespaceAndPath(PtsMod.MODID, "add_terrain_smoothing_overworld")
  );
  private static final ResourceKey<BiomeModifier> BM_NETHER = ResourceKey.create(
      NeoForgeRegistries.Keys.BIOME_MODIFIERS, ResourceLocation.fromNamespaceAndPath(PtsMod.MODID, "add_terrain_smoothing_nether")
  );
  private static final ResourceKey<BiomeModifier> BM_END = ResourceKey.create(
      NeoForgeRegistries.Keys.BIOME_MODIFIERS, ResourceLocation.fromNamespaceAndPath(PtsMod.MODID, "add_terrain_smoothing_end")
  );

  private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
      .add(Registries.CONFIGURED_FEATURE, context -> {
        context.register(CF_KEY, new ConfiguredFeature<>(ModFeatures.SMOOTHING_FEATURE.get(), NoneFeatureConfiguration.NONE));
      })
      .add(Registries.PLACED_FEATURE, context -> {
        HolderGetter<ConfiguredFeature<?, ?>> cfGetter = context.lookup(Registries.CONFIGURED_FEATURE);
        context.register(PF_KEY, new PlacedFeature(cfGetter.getOrThrow(CF_KEY), java.util.List.of()));
      })
      .add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, context -> {
        HolderGetter<PlacedFeature> pfGetter = context.lookup(Registries.PLACED_FEATURE);
        HolderGetter<Biome> biomeGetter = context.lookup(Registries.BIOME);

        context.register(BM_OVERWORLD, new BiomeModifiers.AddFeaturesBiomeModifier(
            biomeGetter.getOrThrow(BiomeTags.IS_OVERWORLD),
            HolderSet.direct(pfGetter.getOrThrow(PF_KEY)),
            GenerationStep.Decoration.UNDERGROUND_DECORATION
        ));
        context.register(BM_NETHER, new BiomeModifiers.AddFeaturesBiomeModifier(
            biomeGetter.getOrThrow(BiomeTags.IS_NETHER),
            HolderSet.direct(pfGetter.getOrThrow(PF_KEY)),
            GenerationStep.Decoration.UNDERGROUND_DECORATION
        ));
        context.register(BM_END, new BiomeModifiers.AddFeaturesBiomeModifier(
            biomeGetter.getOrThrow(BiomeTags.IS_END),
            HolderSet.direct(pfGetter.getOrThrow(PF_KEY)),
            GenerationStep.Decoration.UNDERGROUND_DECORATION
        ));
      });

  /**
   * @param output the pack output
   * @param registries future providing registry lookup
   */
  public PtsWorldGenProvider(PackOutput output, CompletableFuture<net.minecraft.core.HolderLookup.Provider> registries) {
    super(output, registries, BUILDER, Set.of(PtsMod.MODID));
  }
}