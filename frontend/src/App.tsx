import { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import { reportBootDiagnostic } from './utils/bootDiagnostics';
import { isNativePlatform } from './utils/platform';
import { ProtectedRoute } from './components/ProtectedRoute';
import { RequireServerUrl } from './components/RequireServerUrl';
import { ConnectServerPage } from './pages/ConnectServerPage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { CollectionsPage } from './pages/CollectionsPage';
import { CollectionDetailPage } from './pages/CollectionDetailPage';
import { CataloguePage } from './pages/CataloguePage';
import { ItemDetailPage } from './pages/ItemDetailPage';
import { ItemFormPage } from './pages/ItemFormPage';
import { ScanSessionsPage } from './pages/ScanSessionsPage';
import { ScanCapturePage } from './pages/ScanCapturePage';
import { ScanReviewPage } from './pages/ScanReviewPage';
import { AddPhotosPage } from './pages/AddPhotosPage';
import { ThriftSessionsPage } from './pages/ThriftSessionsPage';
import { ThriftCapturePage } from './pages/ThriftCapturePage';

function App() {
  const { initializeAuth } = useAuthStore();

  useEffect(() => {
    reportBootDiagnostic({ event: 'app-effect-ran', isNative: isNativePlatform() });
    initializeAuth();
  }, [initializeAuth]);

  return (
    <Router>
      <Routes>
        <Route path="/connect" element={<ConnectServerPage />} />
        <Route path="/login" element={<RequireServerUrl><LoginPage /></RequireServerUrl>} />
        <Route path="/register" element={<RequireServerUrl><RegisterPage /></RequireServerUrl>} />
        <Route path="/" element={<Navigate to="/collections" replace />} />

        <Route path="/collections" element={<ProtectedRoute><CollectionsPage /></ProtectedRoute>} />
        <Route path="/collections/:id" element={<ProtectedRoute><CollectionDetailPage /></ProtectedRoute>} />

        <Route path="/items" element={<ProtectedRoute><CataloguePage /></ProtectedRoute>} />
        <Route path="/items/new" element={<ProtectedRoute><ItemFormPage /></ProtectedRoute>} />
        <Route path="/items/:id" element={<ProtectedRoute><ItemDetailPage /></ProtectedRoute>} />
        <Route path="/items/:id/edit" element={<ProtectedRoute><ItemFormPage /></ProtectedRoute>} />
        <Route path="/items/:itemId/photos" element={<ProtectedRoute><AddPhotosPage /></ProtectedRoute>} />

        <Route path="/scan" element={<ProtectedRoute><ScanSessionsPage /></ProtectedRoute>} />
        <Route path="/scan/:sessionId" element={<ProtectedRoute><ScanCapturePage /></ProtectedRoute>} />
        <Route path="/scan/:sessionId/review" element={<ProtectedRoute><ScanReviewPage /></ProtectedRoute>} />
        <Route path="/scan/:sessionId/drafts/:draftId/photos" element={<ProtectedRoute><AddPhotosPage /></ProtectedRoute>} />
        <Route path="/thrift" element={<ProtectedRoute><ThriftSessionsPage /></ProtectedRoute>} />
        <Route path="/thrift/:sessionId" element={<ProtectedRoute><ThriftCapturePage /></ProtectedRoute>} />
        <Route path="/thrift/:sessionId/sightings/:sightingId/photos" element={<ProtectedRoute><AddPhotosPage /></ProtectedRoute>} />
      </Routes>
    </Router>
  );
}

export default App;
