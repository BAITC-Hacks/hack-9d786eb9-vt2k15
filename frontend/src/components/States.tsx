export function TableSkeleton() {
  return (
    <div className="panel skeleton" aria-busy="true" aria-label="Загрузка плана заказа">
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="sk-row">
          <div className="sk w40" />
          <div className="sk w10" />
          <div className="spacer" />
          <div className="sk w8" />
        </div>
      ))}
    </div>
  );
}

export function ErrorState({ message, requestId, onRetry }: { message: string; requestId?: string; onRetry: () => void }) {
  return (
    <div className="panel state" role="alert">
      <h2 className="bad-text">Не удалось получить план заказа</h2>
      <p className="muted">{message}</p>
      {requestId && <div className="mono small muted">request_id: {requestId}</div>}
      <button className="btn primary" onClick={onRetry}>Повторить</button>
    </div>
  );
}

export function EmptyState({ onGoImport }: { onGoImport?: () => void }) {
  return (
    <div className="panel state">
      <h2>Заказывать нечего</h2>
      <p className="muted">Бэкенд вернул пустой список: либо остатков хватает, либо выгрузки ещё не загружены.</p>
      {onGoImport && <button className="btn primary" onClick={onGoImport}>Загрузить данные</button>}
    </div>
  );
}
