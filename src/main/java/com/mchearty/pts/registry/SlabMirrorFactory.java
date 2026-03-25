package com.mchearty.pts.registry;

import com.mchearty.pts.PtsMod;
import com.mchearty.pts.block.ModBlocks;
import com.mchearty.pts.block.PtsTerrainSlabBlock;
import com.mchearty.pts.config.PtsConfigService;
import com.mchearty.pts.pack.PtsDynamicPackEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory responsible for dynamic block and item registration of PTS slabs.
 *
 * <p>Scans all registered blocks during {@link RegisterEvent} (lowest priority)
 * and creates mirrored slabs for those matching the configuration. Also registers
 * corresponding {@link BlockItem}s and triggers runtime pack generation.
 */
public class SlabMirrorFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(SlabMirrorFactory.class);

  /** Map of original block ID → generated PTS slab instance (populated before item registration). */
  public static final Map<ResourceLocation, PtsTerrainSlabBlock> PENDING_SLABS = Collections.synchronizedMap(new HashMap<>());

  /**
   * Registers PTS slab blocks for every eligible target block.
   *
   * <p>Executed at {@link EventPriority#LOWEST} during {@code RegisterEvent<Block>}.
   *
   * @param event the registry event
   */
  public static void onRegisterBlocks(RegisterEvent event) {
    if (!event.getRegistryKey().equals(Registries.BLOCK)) return;

    for (Block target : BuiltInRegistries.BLOCK) {
      if (target == Blocks.AIR) continue;

      ResourceLocation id = BuiltInRegistries.BLOCK.getKey(target);
      if (id.getNamespace().equals(PtsMod.MODID)) continue;

      String path = id.getPath().toLowerCase();

      boolean isMatch = false;

      if (PtsConfigService.EXACT_TARGETS.contains(id) || PtsConfigService.TARGET_NAMESPACES.contains(id.getNamespace())) {
        isMatch = true;
      } else if (PtsConfigService.KEYWORD_PATTERN.matcher(path).find()) {
        isMatch = true;
      }

      if (isMatch) {
        try {
          BlockState baseState = target.defaultBlockState();
          if (ModBlocks.isValidForSmoothing(baseState)) {
            PtsTerrainSlabBlock slab = PtsTerrainSlabBlock.create(target, id);

            ResourceLocation slabId = ResourceLocation.fromNamespaceAndPath(PtsMod.MODID,
                id.getNamespace() + "_" + id.getPath() + "_smoothed_slab");

            event.register(Registries.BLOCK, slabId, () -> slab);
            PENDING_SLABS.put(id, slab);

            // Replicate standard mineable tags safely since Tag bindings aren't fully resolved natively yet
            if (baseState.requiresCorrectToolForDrops()) {
              PtsDynamicPackEngine.addTagMapping(BlockTags.MINEABLE_WITH_PICKAXE.location(), slabId);
              PtsDynamicPackEngine.addTagMapping(BlockTags.MINEABLE_WITH_AXE.location(), slabId);
              PtsDynamicPackEngine.addTagMapping(BlockTags.MINEABLE_WITH_SHOVEL.location(), slabId);
              PtsDynamicPackEngine.addTagMapping(BlockTags.MINEABLE_WITH_HOE.location(), slabId);
            }

            // Copy explicitly bound logic if registered statically natively
            target.builtInRegistryHolder().tags().forEach(tagKey -> {
              PtsDynamicPackEngine.addTagMapping(tagKey.location(), slabId);
            });
          }
        } catch (Exception e) {
          LOGGER.error("PTS: Failed to evaluate or construct block slab for target {}", id, e);
        }
      }
    }
  }

  /**
   * Registers {@link BlockItem}s for all pending slabs and triggers dynamic pack
   * generation.
   *
   * <p>Executed at {@link EventPriority#LOWEST} during {@code RegisterEvent<Item>}.
   *
   * @param event the registry event
   */
  public static void onRegisterItems(RegisterEvent event) {
    if (!event.getRegistryKey().equals(Registries.ITEM)) return;

    for (Map.Entry<ResourceLocation, PtsTerrainSlabBlock> entry : PENDING_SLABS.entrySet()) {
      ResourceLocation id = entry.getKey();
      ResourceLocation slabId = ResourceLocation.fromNamespaceAndPath(PtsMod.MODID,
          id.getNamespace() + "_" + id.getPath() + "_smoothed_slab");

      event.register(Registries.ITEM, slabId, () -> new BlockItem(entry.getValue(), new Item.Properties()));
    }

    // Safely evaluate dynamic models after all blocks/items process but BEFORE Pack Discover completes
    PtsDynamicPackEngine.generateOrUpdateRuntimePack(PENDING_SLABS.keySet());
  }
}