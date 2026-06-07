package anon.def9a2a4.corelib;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Shulker;

/**
 * A carrier ArmorStand + Shulker pair that provides collision for a mechanism block.
 * Package-private — consumers use {@link Mechanism#hasCollision(int)} instead.
 */
record ColliderPair(ArmorStand carrier, Shulker shulker, int blockIndex) {}
