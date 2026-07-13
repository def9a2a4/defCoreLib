package anon.def9a2a4.pipes;

public record PipeVariant(String id, BehaviorType behaviorType, int transferIntervalTicks, int itemsPerTransfer) {
    public String getId() { return id; }
    public BehaviorType getBehaviorType() { return behaviorType; }
    public int getTransferIntervalTicks() { return transferIntervalTicks; }
    public int getItemsPerTransfer() { return itemsPerTransfer; }
}
