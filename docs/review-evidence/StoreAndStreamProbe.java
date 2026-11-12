import io.github.yukiykchen.dsh.data.DshSqliteLocalStore;
import io.github.yukiykchen.dsh.data.DshStoreCommand;
import io.github.yukiykchen.dsh.data.DshStoreCommandData;
import io.github.yukiykchen.dsh.data.DshHostStoreQueries;
import io.github.yukiykchen.dsh.data.FakeHostTransport;
import io.github.yukiykchen.dsh.data.FakeHostTransportKt;
import io.github.yukiykchen.dsh.data.DshHostClient;
import io.github.yukiykchen.dsh.model.DshConnectionMode;
import io.github.yukiykchen.dsh.model.DshHostStore;
import io.github.yukiykchen.dsh.feature.home.ControllerTestFixture;
import io.github.yukiykchen.dsh.feature.home.DshCredentialController;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import kotlin.Unit;

public class StoreAndStreamProbe {
    public static void main(String[] args) throws Exception {
        DshHostStore host = new DshHostStore();
        DshHostStoreQueries queries = new DshHostStoreQueries(host);
        host.putPending("approval-rpc", "{\"type\":\"approval/requested\",\"sessionId\":\"s1\",\"approvalId\":\"a1\",\"callId\":\"call-1\"}");
        host.applySessionEvent("s1", 1, "tool/call", "{\"event\":{\"type\":\"tool/call\",\"data\":{\"callId\":\"call-1\",\"arguments\":{\"command\":\"pwd\"}}}}");
        System.out.println("productionEnvelopeApprovalCommand=" + queries.pendingInteractions("s1").getFirst().getCommand());
        host.applySessionEvent("s1", 2, "tool/call", "{\"data\":{\"callId\":\"call-1\",\"arguments\":{\"command\":\"pwd\"}}}");
        System.out.println("testFixtureEnvelopeApprovalCommand=" + queries.pendingInteractions("s1").getFirst().getCommand());

        FakeHostTransport transport = new FakeHostTransport();
        DshHostClient client = FakeHostTransportKt.createTestHostClient(transport);
        transport.completeHandshake();
        ArrayList<String> deltas = new ArrayList<>();
        client.streamReply("test-page", "s1", "probe", (String text, Boolean reasoning) -> { deltas.add(text); return Unit.INSTANCE; }, result -> Unit.INSTANCE, error -> Unit.INSTANCE);
        String rpc = transport.getRequests().get(transport.getRequests().size()-1).getBody().optString("rpcId");
        String frame = "{\"payload\":{\"type\":\"session/event\",\"sessionId\":\"s1\",\"event\":{\"seq\":20,\"type\":\"assistant/chunk\",\"data\":{\"source\":{\"rpcId\":\"" + rpc + "\"},\"chunk\":{\"type\":\"text-delta\",\"text\":\"hello\"}}}}}";
        transport.getSockets().get(0).frame(frame);
        transport.getSockets().get(0).frame(frame);
        System.out.println("duplicateSeqDeltas=" + deltas);
        client.stop();

        ControllerTestFixture page = new ControllerTestFixture();
        DshCredentialController credentials = new DshCredentialController(page.getCtx(), visible -> Unit.INSTANCE, () -> Unit.INSTANCE,
            action -> { page.post(action); action.invoke(); return Unit.INSTANCE; },
            (delay, action) -> { page.schedule(action); return Unit.INSTANCE; });
        credentials.open();
        page.request("llm.providers").fail("unavailable");
        page.getTransport().getSockets().get(0).disconnect();
        page.flush();
        page.getScheduled().get(page.getScheduled().size() - 1).invoke();
        page.getScheduled().clear();
        System.out.println("credentialLoadAfterDisconnect busy=" + credentials.getBusy() + " visible=" + credentials.getVisible() + " errorLength=" + credentials.error().length());
        credentials.dispose();
        page.getClient().stop();

        DshSqliteLocalStore store = new DshSqliteLocalStore(":memory:", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        store.submitAsync(DshStoreCommand.SAVE_SSH_MODE, new DshStoreCommandData(), action -> { action.invoke(); return Unit.INSTANCE; }, error -> {
            entered.countDown();
            completed.countDown();
            return Unit.INSTANCE;
        });
        boolean started = entered.await(2, TimeUnit.SECONDS);
        boolean saved = completed.await(1, TimeUnit.SECONDS);
        System.out.println("actualStoreSubmitEntered=" + started + " actualStoreWriteCompleted=" + saved);
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            for (StackTraceElement item : stack) {
                if (item.getClassName().equals("io.github.yukiykchen.dsh.data.DshSqliteLocalStore") && item.getMethodName().equals("saveLastConnectionMode")) {
                    System.out.println("actualStoreWorker=" + thread.getName() + " state=" + thread.getState());
                    for (StackTraceElement frameItem : stack) System.out.println("  " + frameItem);
                    break;
                }
            }
        });
        System.out.println("actualStoreCommandCommitted=" + saved);
        System.exit(started && saved ? 0 : 1);
    }
}
