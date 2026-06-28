#!/usr/bin/env python3
"""Generate slabs.yml for all Minecraft slab types."""

import os

OUTPUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "slabs.yml")

SLAB_PLACEHOLDER = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzNmYjdjOTJmZTE2NDlkNzZjNGFkOTc5ZWZjZDJjNDYwYjJlOTBiMjMyMGEyMzNlZjg1MTMzZGQ1NmJlZDg2YSJ9fX0="

# (id, display_name, material, block_data, sound_type)
# sound_type: "wood", "stone", "nether_brick", "copper", "nether_wood"
SLABS = [
    # Wood
    ("oak", "Oak", "OAK_SLAB", "minecraft:oak_slab", "wood"),
    ("spruce", "Spruce", "SPRUCE_SLAB", "minecraft:spruce_slab", "wood"),
    ("birch", "Birch", "BIRCH_SLAB", "minecraft:birch_slab", "wood"),
    ("jungle", "Jungle", "JUNGLE_SLAB", "minecraft:jungle_slab", "wood"),
    ("acacia", "Acacia", "ACACIA_SLAB", "minecraft:acacia_slab", "wood"),
    ("dark_oak", "Dark Oak", "DARK_OAK_SLAB", "minecraft:dark_oak_slab", "wood"),
    ("mangrove", "Mangrove", "MANGROVE_SLAB", "minecraft:mangrove_slab", "wood"),
    ("cherry", "Cherry", "CHERRY_SLAB", "minecraft:cherry_slab", "wood"),
    ("pale_oak", "Pale Oak", "PALE_OAK_SLAB", "minecraft:pale_oak_slab", "wood"),
    ("bamboo", "Bamboo", "BAMBOO_SLAB", "minecraft:bamboo_slab", "wood"),
    ("bamboo_mosaic", "Bamboo Mosaic", "BAMBOO_MOSAIC_SLAB", "minecraft:bamboo_mosaic_slab", "wood"),
    ("crimson", "Crimson", "CRIMSON_SLAB", "minecraft:crimson_slab", "nether_wood"),
    ("warped", "Warped", "WARPED_SLAB", "minecraft:warped_slab", "nether_wood"),
    # Stone
    ("stone", "Stone", "STONE_SLAB", "minecraft:stone_slab", "stone"),
    ("cobblestone", "Cobblestone", "COBBLESTONE_SLAB", "minecraft:cobblestone_slab", "stone"),
    ("mossy_cobblestone", "Mossy Cobblestone", "MOSSY_COBBLESTONE_SLAB", "minecraft:mossy_cobblestone_slab", "stone"),
    ("smooth_stone", "Smooth Stone", "SMOOTH_STONE_SLAB", "minecraft:smooth_stone_slab", "stone"),
    ("stone_brick", "Stone Brick", "STONE_BRICK_SLAB", "minecraft:stone_brick_slab", "stone"),
    ("mossy_stone_brick", "Mossy Stone Brick", "MOSSY_STONE_BRICK_SLAB", "minecraft:mossy_stone_brick_slab", "stone"),
    ("granite", "Granite", "GRANITE_SLAB", "minecraft:granite_slab", "stone"),
    ("polished_granite", "Polished Granite", "POLISHED_GRANITE_SLAB", "minecraft:polished_granite_slab", "stone"),
    ("diorite", "Diorite", "DIORITE_SLAB", "minecraft:diorite_slab", "stone"),
    ("polished_diorite", "Polished Diorite", "POLISHED_DIORITE_SLAB", "minecraft:polished_diorite_slab", "stone"),
    ("andesite", "Andesite", "ANDESITE_SLAB", "minecraft:andesite_slab", "stone"),
    ("polished_andesite", "Polished Andesite", "POLISHED_ANDESITE_SLAB", "minecraft:polished_andesite_slab", "stone"),
    # Sandstone
    ("sandstone", "Sandstone", "SANDSTONE_SLAB", "minecraft:sandstone_slab", "stone"),
    ("smooth_sandstone", "Smooth Sandstone", "SMOOTH_SANDSTONE_SLAB", "minecraft:smooth_sandstone_slab", "stone"),
    ("cut_sandstone", "Cut Sandstone", "CUT_SANDSTONE_SLAB", "minecraft:cut_sandstone_slab", "stone"),
    ("red_sandstone", "Red Sandstone", "RED_SANDSTONE_SLAB", "minecraft:red_sandstone_slab", "stone"),
    ("smooth_red_sandstone", "Smooth Red Sandstone", "SMOOTH_RED_SANDSTONE_SLAB", "minecraft:smooth_red_sandstone_slab", "stone"),
    ("cut_red_sandstone", "Cut Red Sandstone", "CUT_RED_SANDSTONE_SLAB", "minecraft:cut_red_sandstone_slab", "stone"),
    # Brick
    ("brick", "Brick", "BRICK_SLAB", "minecraft:brick_slab", "stone"),
    ("mud_brick", "Mud Brick", "MUD_BRICK_SLAB", "minecraft:mud_brick_slab", "stone"),
    # Nether
    ("nether_brick", "Nether Brick", "NETHER_BRICK_SLAB", "minecraft:nether_brick_slab", "stone"),
    ("red_nether_brick", "Red Nether Brick", "RED_NETHER_BRICK_SLAB", "minecraft:red_nether_brick_slab", "stone"),
    # Quartz
    ("quartz", "Quartz", "QUARTZ_SLAB", "minecraft:quartz_slab", "stone"),
    ("smooth_quartz", "Smooth Quartz", "SMOOTH_QUARTZ_SLAB", "minecraft:smooth_quartz_slab", "stone"),
    # Prismarine
    ("prismarine", "Prismarine", "PRISMARINE_SLAB", "minecraft:prismarine_slab", "stone"),
    ("prismarine_brick", "Prismarine Brick", "PRISMARINE_BRICK_SLAB", "minecraft:prismarine_brick_slab", "stone"),
    ("dark_prismarine", "Dark Prismarine", "DARK_PRISMARINE_SLAB", "minecraft:dark_prismarine_slab", "stone"),
    # End / Purpur
    ("end_stone_brick", "End Stone Brick", "END_STONE_BRICK_SLAB", "minecraft:end_stone_brick_slab", "stone"),
    ("purpur", "Purpur", "PURPUR_SLAB", "minecraft:purpur_slab", "stone"),
    # Blackstone
    ("blackstone", "Blackstone", "BLACKSTONE_SLAB", "minecraft:blackstone_slab", "stone"),
    ("polished_blackstone", "Polished Blackstone", "POLISHED_BLACKSTONE_SLAB", "minecraft:polished_blackstone_slab", "stone"),
    ("polished_blackstone_brick", "Polished Blackstone Brick", "POLISHED_BLACKSTONE_BRICK_SLAB", "minecraft:polished_blackstone_brick_slab", "stone"),
    # Deepslate
    ("cobbled_deepslate", "Cobbled Deepslate", "COBBLED_DEEPSLATE_SLAB", "minecraft:cobbled_deepslate_slab", "stone"),
    ("polished_deepslate", "Polished Deepslate", "POLISHED_DEEPSLATE_SLAB", "minecraft:polished_deepslate_slab", "stone"),
    ("deepslate_brick", "Deepslate Brick", "DEEPSLATE_BRICK_SLAB", "minecraft:deepslate_brick_slab", "stone"),
    ("deepslate_tile", "Deepslate Tile", "DEEPSLATE_TILE_SLAB", "minecraft:deepslate_tile_slab", "stone"),
    # Tuff
    ("tuff", "Tuff", "TUFF_SLAB", "minecraft:tuff_slab", "stone"),
    ("polished_tuff", "Polished Tuff", "POLISHED_TUFF_SLAB", "minecraft:polished_tuff_slab", "stone"),
    ("tuff_brick", "Tuff Brick", "TUFF_BRICK_SLAB", "minecraft:tuff_brick_slab", "stone"),
    # Resin
    ("resin_brick", "Resin Brick", "RESIN_BRICK_SLAB", "minecraft:resin_brick_slab", "stone"),
    # Copper
    ("cut_copper", "Cut Copper", "CUT_COPPER_SLAB", "minecraft:cut_copper_slab", "copper"),
    ("exposed_cut_copper", "Exposed Cut Copper", "EXPOSED_CUT_COPPER_SLAB", "minecraft:exposed_cut_copper_slab", "copper"),
    ("weathered_cut_copper", "Weathered Cut Copper", "WEATHERED_CUT_COPPER_SLAB", "minecraft:weathered_cut_copper_slab", "copper"),
    ("oxidized_cut_copper", "Oxidized Cut Copper", "OXIDIZED_CUT_COPPER_SLAB", "minecraft:oxidized_cut_copper_slab", "copper"),
    ("waxed_cut_copper", "Waxed Cut Copper", "WAXED_CUT_COPPER_SLAB", "minecraft:waxed_cut_copper_slab", "copper"),
    ("waxed_exposed_cut_copper", "Waxed Exposed Cut Copper", "WAXED_EXPOSED_CUT_COPPER_SLAB", "minecraft:waxed_exposed_cut_copper_slab", "copper"),
    ("waxed_weathered_cut_copper", "Waxed Weathered Cut Copper", "WAXED_WEATHERED_CUT_COPPER_SLAB", "minecraft:waxed_weathered_cut_copper_slab", "copper"),
    ("waxed_oxidized_cut_copper", "Waxed Oxidized Cut Copper", "WAXED_OXIDIZED_CUT_COPPER_SLAB", "minecraft:waxed_oxidized_cut_copper_slab", "copper"),
]

SOUNDS = {
    "wood":        ("BLOCK_WOOD_PLACE",  "BLOCK_WOOD_BREAK"),
    "nether_wood": ("BLOCK_NETHER_WOOD_PLACE", "BLOCK_NETHER_WOOD_BREAK"),
    "stone":       ("BLOCK_STONE_PLACE", "BLOCK_STONE_BREAK"),
    "copper":      ("BLOCK_COPPER_PLACE", "BLOCK_COPPER_BREAK"),
}


def block_yaml(slab_id, name, material, block_data, sound_type):
    place_sound, break_sound = SOUNDS[sound_type]
    return f"""\
  {slab_id}:
    name: "&f{name} Vertical Slab"
    texture: "@slab_placeholder"
    item_material: {material}
    item_glint: true
    drops: self
    place_sound: {{ sound: {place_sound}, volume: 1.0, pitch: 1.0 }}
    break_sound: {{ sound: {break_sound}, volume: 1.0, pitch: 1.0 }}
    placement:
      allowed_faces: [NORTH, SOUTH, EAST, WEST]
      require_solid: true
    default_state: north
    placement_state_map:
      NORTH: north
      SOUTH: south
      EAST: east
      WEST: west
    states:
      north:
        display_entities:
          - block_data: "{block_data}"
            tag: slab
            transform: {{ left_rotation: [-90, 1, 0, 0], scale: [1, 1.004, 1], translation: [-0.5, -0.5, 0.001] }}
      south:
        display_entities:
          - block_data: "{block_data}"
            tag: slab
            transform: {{ left_rotation: [-90, 1, 0, 0], scale: [1, 1.004, 1], translation: [-0.5, -0.5, 0.501] }}
      east:
        display_entities:
          - block_data: "{block_data}"
            tag: slab
            transform: {{ left_rotation: [90, 0, 0, 1], scale: [1, 1.004, 1], translation: [0.501, -0.5, -0.5] }}
      west:
        display_entities:
          - block_data: "{block_data}"
            tag: slab
            transform: {{ left_rotation: [90, 0, 0, 1], scale: [1, 1.004, 1], translation: [0.001, -0.5, -0.5] }}
    recipes:
      craft:
        shapeless:
          - id: "{slab_id}_vslab_toggle"
            amount: 1
            ingredients:
              - material: {material}
"""


def main():
    lines = []
    lines.append("# Vertical Slabs — generated by scripts/generate_slabs.py\n")
    lines.append("namespace: verticalslabs\n")
    lines.append("textures:")
    lines.append(f'  slab_placeholder: "{SLAB_PLACEHOLDER}"\n')
    lines.append("blocks:\n")

    for slab_id, name, material, block_data, sound_type in SLABS:
        lines.append(block_yaml(slab_id, name, material, block_data, sound_type))

    output = "\n".join(lines)
    with open(OUTPUT, "w") as f:
        f.write(output)
    print(f"Generated {len(SLABS)} vertical slab definitions -> {OUTPUT}")


if __name__ == "__main__":
    main()
