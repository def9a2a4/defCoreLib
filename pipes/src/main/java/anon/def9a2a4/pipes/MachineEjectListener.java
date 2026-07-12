package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.MachineEjectEvent;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;

public class MachineEjectListener implements Listener {

    private final Map<World, PipeManager> pipeManagers;

    public MachineEjectListener(Map<World, PipeManager> pipeManagers) {
        this.pipeManagers = pipeManagers;
    }

    @EventHandler
    public void onMachineEject(MachineEjectEvent event) {
        Block target = event.getTarget();
        PipeManager manager = pipeManagers.get(target.getWorld());
        if (manager == null) return;

        Boolean delivered = manager.deliverFromAbove(target, event.getOutputs());
        if (delivered == null) return;
        event.setResult(delivered
            ? MachineEjectEvent.Result.HANDLED
            : MachineEjectEvent.Result.STALL);
    }
}
