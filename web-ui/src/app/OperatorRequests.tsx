import {useState} from "react";
import {RefreshCw} from "lucide-react";
import type {ControlPlaneClient, OperatorRequestSnapshot} from "../api/controlPlane";
import {TableEmpty} from "./ui";

export type OperatorClient = Pick<ControlPlaneClient, "recheckOperatorRequests" | "closeOperatorRequest">;

/**
 * What is waiting for the operator, with the two things a person can do about it. A request an agent put to the operator is
 * closed by the person once dealt with. A request Synesis raised closes by itself when its condition no longer holds, so
 * the person asks it to look again.
 */
export function OperatorRequests({requests, client}: {requests: OperatorRequestSnapshot[]; client: OperatorClient | null}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const run = async (action: () => Promise<string>) => {
    if (!client || busy) return;
    setBusy(true);
    try {
      setMessage(await action());
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "That did not work.");
    } finally {
      setBusy(false);
    }
  };
  const recheck = () => run(async () => {
    const result = await client!.recheckOperatorRequests();
    if (result.failed) return "Checked again, but the pass failed" + (result.reason ? " (" + result.reason + ")" : "") + ".";
    return result.openRequests === 0 ? "Checked again. Nothing is waiting for you." : "Checked again. " + result.openRequests + (result.openRequests === 1 ? " request is" : " requests are") + " still open.";
  });
  const close = (id: string) => run(async () => {
    await client!.closeOperatorRequest(id);
    return "Closed " + id + ".";
  });
  return (
    <section className="operator-requests" aria-label="Operator requests">
      <div className="toolbar">
        <p className="muted">A request an agent asked is closed here once you have dealt with it. Synesis closes the ones it raised when their condition no longer holds.</p>
        <button className="button button-secondary" type="button" disabled={!client || busy} onClick={() => void recheck()}><RefreshCw size={15}/> Recheck</button>
      </div>
      <div className="data-table-wrap panel">
        <table className="data-table"><thead><tr><th>Request</th><th>Condition</th><th>Raised By</th><th>Summary</th><th>Evidence</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>{requests.length === 0 ? <TableEmpty colSpan={6} text="Nothing is waiting on an operator."/> : requests.map((request) => (
            <tr key={request.id}>
              <td className="mono">{request.id}</td>
              <td>{request.condition}</td>
              <td>{request.raisedBy ? "A lane" : "Synesis"}</td>
              <td className="table-cell-wrap">{request.summary}</td>
              <td className="table-cell-wrap">{request.evidence}</td>
              <td className="table-action-cell">
                {request.raisedBy
                  ? <button className="button button-secondary" type="button" disabled={!client || busy} aria-label={"Close " + request.id} onClick={() => void close(request.id)}>Close</button>
                  : <span className="muted">Closes by itself</span>}
              </td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {message && <p className="action-message" role="status">{message}</p>}
    </section>
  );
}
