import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import App from './App';

describe('foundation shell', () => {
  it('shows backend health and all seven tables', async () => {
    const names = ['categories', 'customers', 'order_items', 'orders', 'payments', 'products', 'refunds'];
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('health') ? { status: 'UP' } : names.map(name => ({ name, columns: [], foreignKeys: [] })) })));
    render(<App />);
    expect(await screen.findByText('UP')).toBeInTheDocument();
    for (const name of names) expect(await screen.findByRole('heading', { name })).toBeInTheDocument();
  });
  it('reports connection failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    render(<App />);
    expect(await screen.findByText('Unavailable')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Schema could not be loaded');
  });
  it('reports HTTP failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }));
    render(<App />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(await screen.findByText('Unavailable')).toBeInTheDocument();
  });
});
