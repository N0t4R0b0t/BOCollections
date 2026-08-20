import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BookOpen, Music, Film, Gamepad2 } from 'lucide-react';
import { getServerUrl, setServerUrl, testServerUrl } from '../utils/serverUrl';
import { updateApiBaseUrl } from '../api/apiClient';

export function ConnectServerPage() {
  const [url, setUrl] = useState(getServerUrl() ?? '');
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setChecking(true);
    try {
      const reachable = await testServerUrl(url);
      if (!reachable) {
        setError("Couldn't reach a BOCollections server at that address. Check the URL and that the server is running.");
        return;
      }
      setServerUrl(url);
      updateApiBaseUrl();
      navigate('/login');
    } finally {
      setChecking(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="flex justify-center items-center gap-1 text-indigo-600 mb-3">
            <BookOpen size={22} /><Music size={20} /><Film size={22} /><Gamepad2 size={20} />
          </div>
          <h1 className="text-2xl font-bold text-gray-900">BOCollections</h1>
          <p className="text-gray-500 text-sm mt-1">Connect to your server</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-xl shadow-sm border border-gray-200 p-6 space-y-4">
          {error && <p className="text-red-600 text-sm bg-red-50 px-3 py-2 rounded-lg">{error}</p>}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Server address</label>
            <input
              type="url"
              required
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              placeholder="https://your-server.example.com"
            />
            <p className="text-xs text-gray-400 mt-1">
              The address of your BOCollections instance — e.g. its Proxmox LXC's IP or domain.
            </p>
          </div>

          <button
            type="submit"
            disabled={checking}
            className="w-full bg-indigo-600 text-white rounded-lg py-2 text-sm font-medium hover:bg-indigo-700 disabled:opacity-50 transition-colors"
          >
            {checking ? 'Connecting…' : 'Connect'}
          </button>
        </form>
      </div>
    </div>
  );
}
