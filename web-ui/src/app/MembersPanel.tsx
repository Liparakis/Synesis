import {useState} from "react";
import {Users} from "lucide-react";
import {ControlPlaneError, type ControlPlaneClient, type MemberSnapshot, type MembershipSnapshot} from "../api/controlPlane";
import {formatDate, shortNode} from "./format";
import {DetailModal, StatusBadge, TableEmpty} from "./ui";

export type MemberClient = Pick<ControlPlaneClient, "removeMember">;

const states: Record<string, {value: string; label: string}> = {
  SELF: {value: "ACTIVE", label: "Here"},
  CONNECTED: {value: "CONNECTED", label: "Connected"},
  DISCONNECTED: {value: "WARNING", label: "Away"},
};

const unknownState = {value: "UNKNOWN", label: "Unknown"};

function removalMessage(error: unknown): string {
  const code = error instanceof ControlPlaneError ? error.code : "";
  if (code === "NOT_A_MEMBER") return "That computer is not a member of this project any more.";
  if (code === "NOT_A_LEADER" || code === "ALREADY_A_MEMBER") return "Only the owner's computer can remove a member.";
  if (code === "MEMBER_IS_THIS_COMPUTER") return "This computer cannot remove itself.";
  return error instanceof Error ? error.message : "The computer could not be removed.";
}

/**
 * The project's computers: who owns it, which are connected and which are away. The owner can remove one, after
 * confirming, because removal ends that computer's open work on every computer.
 */
export function MembersPanel({membership, projectName, client}: {membership: MembershipSnapshot; projectName?: string; client: MemberClient | null}) {
  const [removing, setRemoving] = useState<MemberSnapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const remove = async (member: MemberSnapshot) => {
    if (!client || busy) return;
    setBusy(true);
    try {
      await client.removeMember(member.nodeId);
      setMessage("Removed " + shortNode(member.nodeId) + ". It can no longer propose or pull; its entries stay on the log.");
    } catch (error) {
      setMessage(removalMessage(error));
    } finally {
      setBusy(false);
      setRemoving(null);
    }
  };
  return (
    <section className="members-panel" aria-label="Project computers">
      <div className="toolbar"><h2><Users size={16} aria-hidden="true"/>Computers</h2>{membership.shared && <span className="count-badge">{membership.members.length}</span>}</div>
      {!membership.shared ? <p className="muted">This project runs on this computer only. Create an invitation above to add another.</p> : (
        <div className="data-table-wrap panel">
          <table className="data-table"><thead><tr><th>Computer</th><th>Role</th><th>Connection</th><th>Last entries</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{membership.members.length === 0 ? <TableEmpty colSpan={5} text="No computer is listed."/> : membership.members.map((member) => (
              <tr key={member.nodeId}>
                <td className="mono" title={member.nodeId}>{shortNode(member.nodeId)}{member.self ? " (this computer)" : ""}</td>
                <td>{member.owner ? "Owner" : "Member"}</td>
                <td><StatusBadge {...(states[member.state] ?? unknownState)}/></td>
                <td>{member.self ? "—" : formatDate(member.lastReceived)}</td>
                <td className="table-action-cell">
                  {membership.selfIsOwner && !member.self && <button className="button button-danger" type="button" disabled={!client || busy} aria-label={"Remove " + shortNode(member.nodeId)} onClick={() => setRemoving(member)}>Remove</button>}
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      {message && <p className="action-message" role="status">{message}</p>}
      {removing && <DetailModal title="Remove computer" onClose={() => { if (!busy) setRemoving(null); }}>
        <div className="registry-form">
          <p>Remove <strong className="mono" title={removing.nodeId}>{shortNode(removing.nodeId)}</strong> from {projectName ?? "this project"}?</p>
          <p className="muted">Its open work ends on every computer and it can no longer propose or pull. Its entries stay on the log.</p>
          <div className="registry-form-actions">
            <button className="button button-secondary" type="button" onClick={() => setRemoving(null)} disabled={busy}>Cancel</button>
            <button className="button button-danger" type="button" onClick={() => void remove(removing)} disabled={busy}>Remove computer</button>
          </div>
        </div>
      </DetailModal>}
    </section>
  );
}
