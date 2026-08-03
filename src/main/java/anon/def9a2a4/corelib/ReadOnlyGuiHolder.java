package anon.def9a2a4.corelib;

/**
 * Marker for read-only, throwaway chest-GUI {@link org.bukkit.inventory.InventoryHolder}s (the catalog,
 * the stonecutter-select menu, and the dynamo/rotator mode menus). Their click handlers already cancel
 * every {@code InventoryClickEvent}; {@code CoreLibPlugin.onReadonlyGuiDrag} additionally cancels drags
 * that touch the top inventory of any holder implementing this, closing the last item-loss vector.
 *
 * <p>Real/editable inventories ({@code StorageHolder}, {@code CartStorageHolder}) must NOT implement it.
 * This interface is core-package-private by design: a companion plugin's own read-only menu must ship its
 * own drag guard (see the Pipes {@code FilterGui}).
 */
interface ReadOnlyGuiHolder {
}
