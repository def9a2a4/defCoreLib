package anon.def9a2a4.corelib;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Shulker;

/**
 * A carrier entity + Shulker pair that provides collision for a mechanism block.
 * The carrier is typically a marker ArmorStand (zero height, so shulker rides at exact position).
 * Package-private — consumers use {@link Mechanism#hasCollision(int)} instead.
 */
record ColliderPair(Entity carrier, Shulker shulker, int blockIndex) {}
