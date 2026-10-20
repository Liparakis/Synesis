import {type ReactNode, useEffect, useRef} from "react";
import type {LucideIcon} from "lucide-react";
import {ChevronRight, Copy, X} from "lucide-react";
import {cx, formatStatus, statusTone} from "./format";

export function StatusBadge({value, large, label}: {value: string; large?: boolean; label?: string}) {
  return <span className={cx("status-badge", "status-" + statusTone(value), large && "status-large")}><span className="status-dot"/>{label ?? formatStatus(value)}</span>;
}

export function OverviewSection({title, icon: Icon, action, children}: {title: string; icon: LucideIcon; action?: ReactNode; children: ReactNode}) {
  return <section className="panel overview-section"><div className="toolbar"><h2><Icon size={16} aria-hidden="true"/>{title}</h2>{action}</div>{children}</section>;
}

export function TextAction({children, onClick}: {children: ReactNode; onClick(): void}) {
  return <button className="text-action" type="button" onClick={onClick}>{children}<ChevronRight size={14}/></button>;
}

export function DetailModal({title, onClose, children}: {title: string; onClose(): void; children: ReactNode}) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeButtonRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="detail-modal" role="presentation" onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="panel detail-modal-card" role="dialog" aria-modal="true" aria-labelledby="detail-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="inspector-heading"><span id="detail-modal-title">{title}</span><button ref={closeButtonRef} className="icon-button" type="button" aria-label={`Close ${title.toLowerCase()}`} onClick={onClose}><X size={16}/></button></div>
        {children}
      </section>
    </div>
  );
}

export function CopyField({label, value}: {label: string; value: string}) {
  const copy = async () => {
    try { await navigator.clipboard.writeText(value); } catch { /* Clipboard access is optional in local browser sessions. */ }
  };
  return <div className="copy-field"><span className="form-label">{label}</span><div><code>{value}</code><button className="icon-button" type="button" aria-label={"Copy " + label} onClick={() => void copy()}><Copy size={15}/></button></div></div>;
}

export function EmptyState({icon: Icon, title, detail}: {icon: LucideIcon; title: string; detail: string}) {
  return <div className="empty-state panel"><Icon size={22} aria-hidden="true"/><h2>{title}</h2><p>{detail}</p></div>;
}

export function TableEmpty({colSpan, text}: {colSpan: number; text: string}) {
  return <tr><td className="table-empty" colSpan={colSpan}>{text}</td></tr>;
}
