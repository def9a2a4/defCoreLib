package anon.def9a2a4.corelib;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Consumer hook for mechanism persistence.
 * Allows plugins to save/restore custom data alongside the mechanism's YAML.
 */
public interface MechanismSerializer {

    /** Serialize consumer-specific data into the YAML section. */
    void save(Mechanism mech, ConfigurationSection section);

    /** Restore consumer-specific data from the YAML section. */
    void restore(Mechanism mech, ConfigurationSection section);

    /** Called when all entities recovered after server restart. */
    void onRecoveryComplete(Mechanism mech);

    /** Called when mechanism is disassembled back to blocks. */
    default void onDisassemble(Mechanism mech) {}
}
