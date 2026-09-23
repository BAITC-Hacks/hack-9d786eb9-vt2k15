import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { HttpError, uploadReports } from "../api/client";
import { REPORT_TYPES, guessKind, reportTitle } from "../api/reports";
import type { ImportResult, ReportKind } from "../api/types";
import { fmtInt } from "../format";

interface Attached {
  id: string;
  file: File;
  kind: ReportKind | "";
}

const ACCEPT = ".xlsx,.xls,.csv";
const MAX_MB = 50;
const sizeLabel = (b: number) => (b > 1e6 ? `${(b / 1e6).toFixed(1).replace(".", ",")} МБ` : `${Math.ceil(b / 1e3)} КБ`);

export function ImportPage({ onGoPlan }: { onGoPlan: () => void }) {
  const [attached, setAttached] = useState<Attached[]>([]);
  const [dragOver, setDragOver] = useState(false);
  const [progress, setProgress] = useState(0);
  const [rejected, setRejected] = useState<string[]>([]);
  const input = useRef<HTMLInputElement>(null);
  const qc = useQueryClient();

  const upload = useMutation({
    mutationFn: () =>
      uploadReports(
        attached.map((a) => ({ kind: a.kind as ReportKind, file: a.file })),
        setProgress,
      ),
    onMutate: () => setProgress(0),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["orders"] }),
  });

  function addFiles(list: FileList | null) {
    if (!list) return;
    const bad: string[] = [];
    const next: Attached[] = [];
    for (const file of Array.from(list)) {
      const ext = file.name.split(".").pop()?.toLowerCase();
      if (!ext || !ACCEPT.includes(`.${ext}`)) bad.push(`${file.name}: нужен xlsx, xls или csv`);
      else if (file.size > MAX_MB * 1e6) bad.push(`${file.name}: больше ${MAX_MB} МБ`);
      else next.push({ id: `${file.name}-${file.size}-${file.lastModified}`, file, kind: guessKind(file.name) });
    }
    setRejected(bad);
    setAttached((prev) => {
      const ids = new Set(prev.map((a) => a.id));
      // тип, уже занятый другим файлом, не подставляем автоматически
      const taken = new Set(prev.map((a) => a.kind).filter(Boolean));
      const added = next
        .filter((n) => !ids.has(n.id))
        .map((n) => {
          if (n.kind && taken.has(n.kind)) return { ...n, kind: "" as const };
          if (n.kind) taken.add(n.kind);
          return n;
        });
      return [...prev, ...added];
    });
    upload.reset();
  }

  const setKind = (id: string, kind: ReportKind | "") => {
    setAttached((p) => p.map((a) => (a.id === id ? { ...a, kind } : a)));
    upload.reset();
  };
  const remove = (id: string) => {
    setAttached((p) => p.filter((a) => a.id !== id));
    upload.reset();
  };

  const kindCount = (k: ReportKind) => attached.filter((a) => a.kind === k).length;
  const untyped = attached.filter((a) => !a.kind).length;
  const duplicates = REPORT_TYPES.filter((r) => kindCount(r.kind) > 1);
  const missing = REPORT_TYPES.filter((r) => r.required && kindCount(r.kind) === 0);
  const blocker = !attached.length
    ? "Прикрепите файлы"
    : untyped
      ? `Выберите тип отчёта для ${untyped} ${untyped === 1 ? "файла" : "файлов"}`
      : duplicates.length
        ? `Один тип — один файл: «${duplicates[0].title}» выбран несколько раз`
        : missing.length
          ? `Не хватает обязательных: ${missing.map((m) => `«${m.title}»`).join(", ")}`
          : null;

  const result = upload.data;

  return (
    <>
      <header className="page-head">
        <div>
          <div className="eyebrow">Выгрузки из 1С</div>
          <h1>Загрузка данных</h1>
        </div>
      </header>

      <div className="import-grid">
        <div className="col">
          <label
            className={`dropzone ${dragOver ? "over" : ""}`}
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragOver(false);
              addFiles(e.dataTransfer.files);
            }}
          >
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 16V4" /><path d="M7 9l5-5 5 5" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" /></svg>
            <div className="grow">
              <div className="strong">Перетащите файлы сюда или выберите на компьютере</div>
              <div className="muted small">xlsx, xls или csv до {MAX_MB} МБ. Тип отчёта подставится по имени файла — проверьте его.</div>
            </div>
            <span className="btn">Выбрать файлы</span>
            <input
              ref={input}
              type="file"
              multiple
              accept={ACCEPT}
              className="sr-only"
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

          <section className="panel" aria-label="Прикреплённые файлы">
            <div className="panel-head">
              <h2>Файлы</h2>
              <span className="muted small">{attached.length ? `прикреплено: ${attached.length}` : "пока пусто"}</span>
            </div>
            {!attached.length ? (
              <div className="empty-filter">Прикрепите выгрузки — для каждой выберите тип отчёта.</div>
            ) : (
              <ul className="file-list">
                {attached.map((a) => {
                  const dup = a.kind && kindCount(a.kind) > 1;
                  const selectId = `kind-${a.id}`;
                  return (
                    <li key={a.id}>
                      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="muted"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></svg>
                      <div className="grow file-name">
                        <div className="strong ellipsis" title={a.file.name}>{a.file.name}</div>
                        <div className="muted small mono">{sizeLabel(a.file.size)}</div>
                      </div>
                      <label className="sr-only" htmlFor={selectId}>Тип отчёта для {a.file.name}</label>
                      <select
                        id={selectId}
                        className={`select ${!a.kind || dup ? "invalid" : ""}`}
                        value={a.kind}
                        onChange={(e) => setKind(a.id, e.target.value as ReportKind | "")}
                        disabled={upload.isPending}
                      >
                        <option value="">Выберите тип отчёта…</option>
                        {REPORT_TYPES.map((r) => (
                          <option key={r.kind} value={r.kind}>
                            {r.title}
                            {r.required ? " *" : ""}
                          </option>
                        ))}
                      </select>
                      <button
                        className="icon-btn"
                        aria-label={`Убрать ${a.file.name}`}
                        onClick={() => remove(a.id)}
                        disabled={upload.isPending}
                      >
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          <div className="send-bar">
            <div className="grow small">
              {upload.isPending ? (
                <div className="progress" role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Загрузка файлов">
                  <div style={{ width: `${Math.max(progress, 0.05) * 100}%` }} />
                </div>
              ) : (
                <span className={blocker ? "muted" : "ok-text"}>
                  {blocker ?? (result ? `Отправлено в ${new Date(result.uploaded_at).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })}` : "Всё готово к отправке")}
                </span>
              )}
            </div>
            <button className="btn primary" disabled={!!blocker || upload.isPending} onClick={() => upload.mutate()}>
              {upload.isPending ? `Отправляю… ${Math.round(progress * 100)}%` : result ? "Отправить заново" : "Отправить на бэкенд"}
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
        </div>

        <aside className="col">
          {result ? <ResultPanel result={result} onGoPlan={onGoPlan} /> : <Checklist kindCount={kindCount} />}
        </aside>
      </div>
    </>
  );
}

function Checklist({ kindCount }: { kindCount: (k: ReportKind) => number }) {
  return (
    <section className="panel" aria-label="Какие отчёты нужны">
      <div className="panel-head">
        <h2>Какие отчёты нужны</h2>
        <span className="muted small">* обязательные</span>
      </div>
      <ul className="check-list">
        {REPORT_TYPES.map((r) => {
          const n = kindCount(r.kind);
          const state = n === 1 ? "done" : n > 1 ? "dup" : r.required ? "need" : "opt";
          return (
            <li key={r.kind} className={state}>
              <span className="mark" aria-hidden="true">{state === "done" ? "✓" : state === "dup" ? "!" : ""}</span>
              <div className="grow">
                <div className="strong">
                  {r.title}
                  {r.required ? " *" : ""}
                </div>
                <div className="muted small">{r.hint}</div>
              </div>
              <span className="small muted">{state === "done" ? "прикреплён" : state === "dup" ? "несколько файлов" : r.required ? "нужен" : "по желанию"}</span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function ResultPanel({ result, onGoPlan }: { result: ImportResult; onGoPlan: () => void }) {
  const tone = { ok: "ok", warning: "warn", error: "bad" } as const;
  const label = { ok: "Готов", warning: "Есть замечания", error: "Ошибка" } as const;
  return (
    <section className="panel" aria-label="Результат проверки">
      <div className="panel-head">
        <h2>Проверка данных</h2>
        <span className={`pill ${result.can_calculate ? "ok" : "bad"}`}>{result.can_calculate ? "Можно считать" : "Нужно исправить"}</span>
      </div>
      <ul className="file-list compact">
        {result.files.map((f) => (
          <li key={f.kind}>
            <div className="grow">
              <div className="strong">{reportTitle(f.kind)}</div>
              <div className="muted small ellipsis" title={f.filename}>{f.filename}</div>
            </div>
            <span className="mono small muted">{fmtInt(f.rows)} стр.</span>
            <span className={`pill ${tone[f.status]}`}>{label[f.status]}</span>
          </li>
        ))}
      </ul>
      {result.issues.length > 0 && (
        <ul className="issues">
          {result.issues.map((i) => (
            <li key={i.code} className={i.severity}>
              <span className="dot" aria-hidden="true" />
              <span className="grow">{i.message}</span>
              <span className="mono small">{fmtInt(i.count)}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="panel-foot">
        <button className="btn primary" disabled={!result.can_calculate} onClick={onGoPlan}>
          Перейти к плану заказа
        </button>
      </div>
    </section>
  );
}
