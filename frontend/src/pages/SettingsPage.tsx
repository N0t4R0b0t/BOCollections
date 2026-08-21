import { useState } from 'react';
import { RefreshCw, Download, Trash2 } from 'lucide-react';
import { apiClient } from '../api/apiClient';
import { AppLayout } from '../components/layout/AppLayout';
import { Spinner } from '../components/ui/Spinner';
import { apiError } from '../utils/apiError';
import { downloadBlob } from '../utils/downloadBlob';

export function SettingsPage() {
  const [clearing, setClearing] = useState(false);
  const [clearResult, setClearResult] = useState<string | null>(null);
  const [clearError, setClearError] = useState<string | null>(null);

  const [logs, setLogs] = useState<string | null>(null);
  const [logsLoading, setLogsLoading] = useState(false);
  const [logsError, setLogsError] = useState<string | null>(null);

  const handleClearCache = async () => {
    if (!confirm('Clear the scanner cache? Every cached barcode lookup will be re-fetched on next scan.')) return;
    setClearing(true);
    setClearError(null);
    setClearResult(null);
    try {
      const res = await apiClient.clearScannerCache();
      setClearResult(`Cleared ${res.data.cleared} cached ${res.data.cleared === 1 ? 'entry' : 'entries'}.`);
    } catch (e) {
      setClearError(apiError(e, 'Failed to clear scanner cache'));
    } finally {
      setClearing(false);
    }
  };

  const handleTailLogs = async () => {
    setLogsLoading(true);
    setLogsError(null);
    try {
      const res = await apiClient.tailLogs(200);
      setLogs(res.data);
    } catch (e) {
      setLogsError(apiError(e, 'Failed to load logs'));
    } finally {
      setLogsLoading(false);
    }
  };

  const handleDownloadLogs = async () => {
    try {
      const res = await apiClient.downloadLogs();
      downloadBlob(res.data, 'bocollections-backend.log');
    } catch (e) {
      setLogsError(apiError(e, 'Failed to download logs'));
    }
  };

  return (
    <AppLayout>
      <div className="p-6 max-w-3xl mx-auto space-y-6">
        <h1 className="text-2xl font-bold text-gray-900">Settings</h1>

        <section className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
          <h2 className="text-base font-semibold text-gray-900 mb-1">Scanner cache</h2>
          <p className="text-sm text-gray-500 mb-4">
            Clears every cached barcode lookup (both real matches and confirmed misses), forcing
            the next scan of any barcode to re-check the external lookup chain from scratch.
            Doesn't touch your catalogue or collections.
          </p>
          <button
            onClick={handleClearCache}
            disabled={clearing}
            className="flex items-center gap-2 bg-red-50 text-red-600 hover:bg-red-100 rounded-lg px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
          >
            {clearing ? <Spinner size="sm" /> : <Trash2 size={16} />}
            Clear scanner cache
          </button>
          {clearResult && <p className="text-sm text-green-600 mt-3">{clearResult}</p>}
          {clearError && <p className="text-sm text-red-600 mt-3">{clearError}</p>}
        </section>

        <section className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
          <h2 className="text-base font-semibold text-gray-900 mb-1">Backend logs</h2>
          <p className="text-sm text-gray-500 mb-4">
            View the most recent log lines, or download the full current log file.
          </p>
          <div className="flex items-center gap-2 mb-4">
            <button
              onClick={handleTailLogs}
              disabled={logsLoading}
              className="flex items-center gap-2 bg-gray-900 text-white hover:bg-gray-800 rounded-lg px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
            >
              {logsLoading ? <Spinner size="sm" /> : <RefreshCw size={16} />}
              {logs === null ? 'Tail last 200 lines' : 'Refresh'}
            </button>
            <button
              onClick={handleDownloadLogs}
              className="flex items-center gap-2 bg-gray-100 text-gray-700 hover:bg-gray-200 rounded-lg px-4 py-2 text-sm font-medium transition-colors"
            >
              <Download size={16} />
              Download full log
            </button>
          </div>
          {logsError && <p className="text-sm text-red-600 mb-3">{logsError}</p>}
          {logs !== null && (
            <pre className="bg-gray-900 text-gray-100 text-xs rounded-lg p-4 overflow-auto max-h-[28rem] whitespace-pre-wrap break-words">
              {logs}
            </pre>
          )}
        </section>
      </div>
    </AppLayout>
  );
}
