import { useEffect, useState } from 'react';

type Table = { name: string; columns: { name: string; type: string; primaryKey: boolean; nullable: boolean }[]; foreignKeys: { column: string; referencedTable: string; referencedColumn: string }[] };

async function getJson<T>(path: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(path, { signal });
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  return response.json() as Promise<T>;
}

export default function App() {
  const [status, setStatus] = useState('Checking…');
  const [tables, setTables] = useState<Table[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    getJson<{ status: string }>('/api/v1/health', controller.signal)
      .then(data => setStatus(data.status))
      .catch(() => { if (!controller.signal.aborted) setStatus('Unavailable'); });
    getJson<Table[]>('/api/v1/schema/tables', controller.signal)
      .then(setTables)
      .catch(() => { if (!controller.signal.aborted) setError('Schema could not be loaded. Check that the backend and database are running, then reload.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);
  return <main className="mx-auto max-w-5xl px-6 py-12">
    <p className="text-sm font-semibold uppercase tracking-widest text-teal-700">AI Data Analyst · Foundation</p>
    <h1 className="mt-3 text-4xl font-bold">Explore the commerce schema</h1>
    <p className="mt-4 text-slate-600">Discover the tables and relationships behind the sample store.</p>
    <p role="status" className="my-8 rounded-xl border border-slate-200 bg-white p-4">API status: <strong>{status}</strong></p>
    <h2 className="mb-4 text-2xl font-semibold">Database tables</h2>
    {loading && <p>Loading schema…</p>}
    {error && <p role="alert" className="rounded-lg bg-red-50 p-4 text-red-800">{error}</p>}
    {!loading && !error && tables.length === 0 && <p>No tables available.</p>}
    <ul className="grid gap-4 md:grid-cols-2">{tables.map(table => <li key={table.name} className="rounded-xl border border-slate-200 bg-white p-5">
      <h3 className="font-mono text-lg font-semibold">{table.name}</h3>
      <ul className="mt-3 space-y-2">{table.columns.map(column => <li key={column.name} className="flex flex-wrap justify-between gap-2 text-sm">
        <span>{column.name}{column.primaryKey ? ' · PK' : ''}</span><span className="text-slate-500">{column.type}{column.nullable ? ' · nullable' : ''}</span>
      </li>)}</ul>
      {table.foreignKeys.map(fk => <p key={fk.column} className="mt-3 text-xs text-teal-800">{fk.column} → {fk.referencedTable}.{fk.referencedColumn}</p>)}
    </li>)}</ul>
    <p className="mt-8 text-sm text-slate-500">Phase 1: schema discovery. Natural-language analysis is not enabled yet.</p>
  </main>;
}
