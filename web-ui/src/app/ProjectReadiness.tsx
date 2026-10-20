import {useState} from "react";
import {ClipboardCheck} from "lucide-react";
import type {Snapshot} from "../api/controlPlane";
import {attentionCount, nextCommand, readinessChecks, type ReadinessCheck, type ReadinessStatus} from "./readiness";
import {CopyField, DetailModal, StatusBadge} from "./ui";

const badges: Record<ReadinessStatus, {value: string; label: string}> = {
  ok: {value: "OK", label: "Ready"},
  attention: {value: "WARNING", label: "Needs attention"},
  pending: {value: "UNKNOWN", label: "Not yet"},
};

/** The readiness checks as one list: what each says, the command that fixes it when there is one, and where it stands. */
export function ReadinessChecks({checks}: {checks: ReadinessCheck[]}) {
  return (
    <ul className="readiness-list" aria-label="Readiness checks">
      {checks.map((check) => (
        <li className="readiness-row" key={check.id}>
          <div className="readiness-copy">
            <strong>{check.label}</strong>
            <span className="muted">{check.detail}</span>
            {check.fix && check.status === "attention" && <code className="readiness-fix">{check.fix}</code>}
          </div>
          <StatusBadge value={badges[check.status].value} label={badges[check.status].label}/>
        </li>
      ))}
    </ul>
  );
}

/** What a project nobody has worked in yet shows instead of empty tables: what Synesis is, whether it is ready, and the next command. */
export function FirstRunView({snapshot}: {snapshot: Snapshot}) {
  return (
    <section className="first-run panel" aria-label="First run">
      <p className="eyebrow">Welcome to Synesis</p>
      <h2>Several coding agents work in one repository without overwriting each other.</h2>
      <p className="muted">Agents claim the files they change, review each other's work, and only reviewed work reaches main. This page shows what is happening and what needs you.</p>
      <ReadinessChecks checks={readinessChecks(snapshot)}/>
      <CopyField label="Next, run this in the project folder" value={nextCommand(snapshot)}/>
      <p className="muted">Or open claude or codex in the project folder and give it a task. Synesis never invents the task.</p>
    </section>
  );
}

/** The always-available entry in the project rail: the number of checks that need attention, and the same checks in a dialog. */
export function ReadinessEntry({snapshot}: {snapshot: Snapshot}) {
  const [open, setOpen] = useState(false);
  const checks = readinessChecks(snapshot);
  const attention = attentionCount(checks);
  return (
    <>
      <button className="nav-item" type="button" onClick={() => setOpen(true)} aria-haspopup="dialog">
        <ClipboardCheck size={16} aria-hidden="true"/>
        <span>Readiness</span>
        {attention > 0 && <span className="nav-count tone-warn" aria-label={attention + " need attention"}>{attention}</span>}
      </button>
      {open && <DetailModal title="Readiness" onClose={() => setOpen(false)}><ReadinessChecks checks={checks}/></DetailModal>}
    </>
  );
}
