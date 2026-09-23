import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { HttpError, uploadReports } from "../api/client";
import {
  REPORT_TYPES,
  SUPPLIERS,
  TOTAL_FILES,
  backendField,
  guessKind,
  guessSupplier,
  reportTitle,
  slotKey,
  supplierName,
} from "../api/reports";
import type { ReportKind, UploadResponse } from "../api/types";
import { fmtInt } from "../format";

interface Slotted {
  file: File;
  /** как файл попал в слот: auto — по имени, free — единственный свободный слот этого типа, manual — вручную */
  how: "auto" | "free" | "manual";
}

const ACCEPT = ".xlsx,.xls,.csv";
const MAX_MB = 50;
const sizeLabel = (b: number) => (b > 1e6 ? `${(b / 1e6).toFixed(1).replace(".", ",")} МБ` : `${Math.ceil(b / 1e3)} КБ`);
const fileId = (f: File) => `${f.name}-${f.size}`;

function checkFile(f: File): string | null {
  const ext = f.name.split(".").pop()?.toLowerCase();
  if (!ext || !ACCEPT.includes(`.${ext}`)) return `${f.name}: нужен xlsx, xls или csv`;
  if (f.size > MAX_MB * 1e6) return `${f.name}: больше ${MAX_MB} МБ`;
  return null;
}

export function ImportPage({ onGoPlan }: { onGoPlan: () => void }) {
  const [slots, setSlots] = useState<Record<string, Slotted>>({});
  const [unsorted, setUnsorted] = useState<File[]>([]);
  const [rejected, setRejected] = useState<string[]>([]);
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [sentAt, setSentAt] = useState<Date | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const qc = useQueryClient();

  const upload = useMutation({
    mutationFn: () =>
      uploadReports(
        // отправляем только полностью заполненных поставщиков (всё или ничего)
        SUPPLIERS.filter((s) => REPORT_TYPES.every((r) => slots[slotKey(s.id, r.kind)])).flatMap((s) =>
          REPORT_TYPES.map((r) => ({ supplierId: s.id, kind: r.kind, file: slots[slotKey(s.id, r.kind)].file })),
        ),
        setProgress,
      ),
    onMutate: () => setProgress(0),
    onSuccess: () => {
      setSentAt(new Date());
      qc.invalidateQueries({ queryKey: ["orders"] });
    },
  });

  /** Раскладывает файлы по слотам: поставщик и тип — по имени файла. */
  function addFiles(list: FileList | File[] | null) {
    if (!list) return;
    const bad: string[] = [];
    const next = { ...slots };
    const rest: File[] = [...unsorted];
    const known = new Set([...Object.values(next).map((s) => fileId(s.file)), ...rest.map(fileId)]);

    // сначала файлы с поставщиком в имени, затем без — тогда «свободный слот» определяется правильно
    const ordered = Array.from(list).sort((a, b) => Number(!guessSupplier(a.name)) - Number(!guessSupplier(b.name)));
    for (const file of ordered) {
      const err = checkFile(file);
      if (err) {
        bad.push(err);
        continue;
      }
      if (known.has(fileId(file))) continue;
      known.add(fileId(file));
      const kind = guessKind(file.name);
      const supplier = guessSupplier(file.name);
      if (kind && supplier && !next[slotKey(supplier, kind)]) {
        next[slotKey(supplier, kind)] = { file, how: "auto" };
        continue;
      }
      if (kind && !supplier) {
        // поставщик не указан в имени — кладём, только если свободен ровно один слот этого типа
        const free = SUPPLIERS.filter((s) => !next[slotKey(s.id, kind)]);
        if (free.length === 1) {
          next[slotKey(free[0].id, kind)] = { file, how: "free" };
          continue;
        }
      }
      rest.push(file);
    }
    setSlots(next);
    setUnsorted(rest);
    setRejected(bad);
    upload.reset();
  }

  function putInSlot(key: string, file: File, how: Slotted["how"] = "manual") {
    const err = checkFile(file);
    if (err) {
      setRejected([err]);
      return;
    }
    setRejected([]);
    const prev = slots[key];
    setSlots({ ...slots, [key]: { file, how } });
    setUnsorted((u) => {
      const rest = u.filter((f) => fileId(f) !== fileId(file));
      // вытесненный из слота файл не теряем — возвращаем в «Не распознаны»
      return prev && fileId(prev.file) !== fileId(file) ? [...rest, prev.file] : rest;
    });
    upload.reset();
  }

  function clearSlot(key: string) {
    setSlots((p) => {
      const n = { ...p };
      delete n[key];
      return n;
    });
    upload.reset();
  }

  const filled = Object.keys(slots).length;
  // каждый поставщик — всё или ничего; отправить можно, если полностью заполнен хотя бы один
  const perSupplier = SUPPLIERS.map((s) => ({
    supplier: s,
    count: REPORT_TYPES.filter((r) => slots[slotKey(s.id, r.kind)]).length,
  }));
  const complete = perSupplier.filter((p) => p.count === REPORT_TYPES.length);
  const partial = perSupplier.filter((p) => p.count > 0 && p.count < REPORT_TYPES.length);
  const blocker = partial.length
    ? `${partial
        .map((p) => `${p.supplier.name}: не хватает ${REPORT_TYPES.length - p.count} из ${REPORT_TYPES.length}`)
        .join("; ")} — заполните поставщика целиком или уберите его файлы`
    : complete.length === 0
      ? `Загрузите все ${REPORT_TYPES.length} отчётов хотя бы одного поставщика`
      : unsorted.length
        ? `Лишние файлы: ${unsorted.length} — разложите или уберите`
        : null;
  const readyLabel =
    complete.length === SUPPLIERS.length
      ? `Готово: оба поставщика, ${complete.length * REPORT_TYPES.length} файлов`
      : `Готово: ${complete.map((p) => p.supplier.name).join(", ")} · ${complete.length * REPORT_TYPES.length} файлов`;
  const result = upload.data;
  const busy = upload.isPending;

  return (
    <>
      <header className="page-head">
        <div>
          <div className="eyebrow">Выгрузки из 1С · по 6 отчётов на поставщика</div>
          <h1>Загрузка данных</h1>
        </div>
        <div className="counter" aria-live="polite">
          <span className="mono strong">{filled}</span>
          <span className="muted"> / {TOTAL_FILES} файлов</span>
        </div>
      </header>

      <label
        className={`dropzone ${dragOver === "all" ? "over" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver("all");
        }}
        onDragLeave={() => setDragOver(null)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(null);
          addFiles(e.dataTransfer.files);
        }}
      >
        <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 16V4" /><path d="M7 9l5-5 5 5" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" /></svg>
        <div className="grow">
          <div className="strong">Перетащите файлы — по {REPORT_TYPES.length} отчётов на поставщика</div>
          <div className="muted small">
            Достаточно одного поставщика целиком; можно загрузить обоих ({TOTAL_FILES} файлов). Поставщик и тип отчёта определятся по имени файла. Что не распознается — попадёт в список ниже. xlsx, xls или csv до {MAX_MB} МБ.
          </div>
        </div>
        <span className="btn">Выбрать файлы</span>
        <input
          ref={input}
          type="file"
          multiple
          accept={ACCEPT}
          className="sr-only"
          disabled={busy}
          onChange={(e) => {
            addFiles(e.target.files);
            if (input.current) input.current.value = "";
          }}
        />
      </label>

      {rejected.length > 0 && (
        <div className="banner bad" role="alert">
          <span>Не добавлены: {rejected.join("; ")}</span>
        </div>
      )}

      {unsorted.length > 0 && (
        <section className="panel unsorted" aria-label="Не распознаны">
          <div className="panel-head">
            <h2>Не распознаны · {unsorted.length}</h2>
            <span className="muted small">выберите, куда положить файл</span>
          </div>
          <ul className="file-list">
            {unsorted.map((f) => {
              const id = `put-${fileId(f)}`;
              return (
                <li key={fileId(f)}>
                  <FileIcon />
                  <div className="grow file-name">
                    <div className="strong ellipsis" title={f.name}>{f.name}</div>
                    <div className="muted small mono">{sizeLabel(f.size)}</div>
                  </div>
                  <label className="sr-only" htmlFor={id}>Куда положить {f.name}</label>
                  <select
                    id={id}
                    className="select invalid"
                    value=""
                    disabled={busy}
                    onChange={(e) => e.target.value && putInSlot(e.target.value, f)}
                  >
                    <option value="">Куда положить…</option>
                    {SUPPLIERS.map((s) => (
                      <optgroup key={s.id} label={s.name}>
                        {REPORT_TYPES.map((r) => {
                          const k = slotKey(s.id, r.kind);
                          return (
                            <option key={k} value={k}>
                              {r.title}
                              {slots[k] ? " (заменить)" : ""}
                            </option>
                          );
                        })}
                      </optgroup>
                    ))}
                  </select>
                  <button
                    className="icon-btn"
                    aria-label={`Убрать ${f.name}`}
                    disabled={busy}
                    onClick={() => setUnsorted((u) => u.filter((x) => fileId(x) !== fileId(f)))}
                  >
                    <CloseIcon />
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      <div className="supplier-grid">
        {SUPPLIERS.map((s) => {
          const count = REPORT_TYPES.filter((r) => slots[slotKey(s.id, r.kind)]).length;
          return (
            <section key={s.id} className="panel" aria-label={s.name}>
              <div className="panel-head">
                <h2>{s.name}</h2>
                <span className={`pill ${count === REPORT_TYPES.length ? "ok" : count > 0 ? "warn" : "muted"}`}>
                  {count} / {REPORT_TYPES.length}
                </span>
              </div>
              <ul className="slot-list">
                {REPORT_TYPES.map((r) => {
                  const key = slotKey(s.id, r.kind);
                  return (
                    <Slot
                      key={key}
                      slotId={key}
                      title={r.title}
                      hint={r.hint}
                      slotted={slots[key]}
                      supplierId={s.id}
                      kind={r.kind}
                      over={dragOver === key}
                      busy={busy}
                      onDragState={(on) => setDragOver(on ? key : null)}
                      onFile={(f) => putInSlot(key, f)}
                      onClear={() => clearSlot(key)}
                    />
                  );
                })}
              </ul>
            </section>
          );
        })}
      </div>

      <div className="send-bar">
        <div className="grow small">
          {busy ? (
            <div className="progress" role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Загрузка файлов">
              <div style={{ width: `${Math.max(progress, 0.05) * 100}%` }} />
            </div>
          ) : (
            <span className={blocker ? "muted" : "ok-text"}>
              {blocker ??
                (result && sentAt
                  ? `Отправлено в ${sentAt.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })}`
                  : readyLabel)}
            </span>
          )}
        </div>
        <button className="btn primary" disabled={!!blocker || busy} onClick={() => upload.mutate()}>
          {busy ? `Отправляю… ${Math.round(progress * 100)}%` : result ? "Отправить заново" : "Отправить на бэкенд"}
        </button>
      </div>

      {upload.isError && (
        <div className="banner bad" role="alert">
          <span>
            Не удалось загрузить: {upload.error.message}
            {upload.error instanceof HttpError && upload.error.requestId ? ` (request_id: ${upload.error.requestId})` : ""}
          </span>
          <button className="link" onClick={() => upload.mutate()}>Повторить</button>
        </div>
      )}

      {result && <ResultPanel result={result} onGoPlan={onGoPlan} />}
    </>
  );
}

interface SlotProps {
  slotId: string;
  title: string;
  hint: string;
  slotted?: Slotted;
  supplierId: string;
  kind: ReportKind;
  over: boolean;
  busy: boolean;
  onDragState: (on: boolean) => void;
  onFile: (f: File) => void;
  onClear: () => void;
}

function Slot({ slotId, title, hint, slotted, supplierId, kind, over, busy, onDragState, onFile, onClear }: SlotProps) {
  const ref = useRef<HTMLInputElement>(null);
  const inputId = `file-${slotId}`;
  // предупреждаем, если имя файла указывает на другого поставщика или другой тип отчёта
  let warn: string | null = null;
  if (slotted) {
    const s = guessSupplier(slotted.file.name);
    const k = guessKind(slotted.file.name);
    if (s && s !== supplierId) warn = `Похоже на файл ${supplierName(s)}`;
    else if (k && k !== kind) warn = `Похоже на «${reportTitle(k)}»`;
    else if (slotted.how === "free") warn = "Поставщик не указан в имени — проверьте";
  }
  return (
    <li
      className={`slot ${slotted ? "filled" : ""} ${over ? "over" : ""}`}
      onDragOver={(e) => {
        e.preventDefault();
        e.stopPropagation();
        onDragState(true);
      }}
      onDragLeave={() => onDragState(false)}
      onDrop={(e) => {
        e.preventDefault();
        e.stopPropagation();
        onDragState(false);
        const f = e.dataTransfer.files[0];
        if (f) onFile(f);
      }}
    >
      <span className="mark" aria-hidden="true">{slotted ? "✓" : ""}</span>
      <div className="grow slot-body">
        <div className="strong">{title}</div>
        {slotted ? (
          <div className="small ellipsis" title={slotted.file.name}>
            <span className="muted">{slotted.file.name}</span>
            <span className="mono muted"> · {sizeLabel(slotted.file.size)}</span>
          </div>
        ) : (
          <div className="muted small">{hint}</div>
        )}
        {warn && <div className="slot-warn small">{warn}</div>}
      </div>
      <label htmlFor={inputId} className={`btn small-btn ${busy ? "disabled" : ""}`}>
        {slotted ? "Заменить" : "Прикрепить"}
      </label>
      <input
        id={inputId}
        ref={ref}
        type="file"
        accept={ACCEPT}
        className="sr-only"
        disabled={busy}
        aria-label={`${title}: выбрать файл`}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onFile(f);
          if (ref.current) ref.current.value = "";
        }}
      />
      {slotted && (
        <button className="icon-btn" aria-label={`Убрать файл: ${title}`} onClick={onClear} disabled={busy}>
          <CloseIcon />
        </button>
      )}
    </li>
  );
}

function ResultPanel({ result, onGoPlan }: { result: UploadResponse; onGoPlan: () => void }) {
  // показываем только тех поставщиков, чьи отчёты пришли (можно грузить одного)
  const shown = SUPPLIERS.filter((s) => REPORT_TYPES.some((r) => result.recordCounts[backendField(s.id, r.kind)] !== undefined));
  return (
    <section className="panel" aria-label="Результат проверки">
      <div className="panel-head">
        <h2>Проверка данных</h2>
        <span className="pill ok">Принято файлов: {result.totalFiles}</span>
      </div>
      <div className="supplier-grid inner">
        {shown.map((s) => (
          <div key={s.id}>
            <h3 className="sub-head">{s.name}</h3>
            <ul className="file-list compact">
              {REPORT_TYPES.map((r) => {
                const count = result.recordCounts[backendField(s.id, r.kind)];
                return (
                  <li key={r.kind}>
                    <div className="grow">
                      <div className="strong">{r.title}</div>
                    </div>
                    <span className="mono small muted">{count === undefined ? "—" : `${fmtInt(count)} зап.`}</span>
                    <span className="pill ok">Разобран</span>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>
      {result.issues.length > 0 ? (
        <Issues items={result.issues} />
      ) : (
        <p className="muted small">Замечаний к ячейкам нет.</p>
      )}
      <div className="panel-foot">
        <button className="btn primary" onClick={onGoPlan}>
          Перейти к плану заказа
        </button>
      </div>
    </section>
  );
}

function Issues({ items }: { items: UploadResponse["issues"] }) {
  return (
    <ul className="issues">
      {items.map((i, idx) => (
        <li key={`${i.fileName}-${i.cell ?? idx}`} className="warning">
          <span className="dot" aria-hidden="true" />
          <span className="grow">{i.message}</span>
          <span className="mono small muted">{[i.fileName, i.sheet, i.cell].filter(Boolean).join(" · ")}</span>
        </li>
      ))}
    </ul>
  );
}

function FileIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="muted"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></svg>
  );
}

function CloseIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
  );
}
