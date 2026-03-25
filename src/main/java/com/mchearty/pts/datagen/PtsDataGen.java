package com.mchearty.pts.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Central data-generation entry point for PTS.
 *
 * <p>Registers the worldgen provider that supplies configured features, placed
 * features, and biome modifiers for terrain smoothing.
 */
public class PtsDataGen {

  /**
   * Called by NeoForge during data generation to add all PTS providers.
   *
   * @param event the GatherDataEvent
   */
  public static void gatherData(GatherDataEvent event) {
    DataGenerator gen = event.getGenerator();
    PackOutput output = gen.getPackOutput();
    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();

    gen.addProvider(event.includeServer(), new PtsWorldGenProvider(output, provider));
  }
}