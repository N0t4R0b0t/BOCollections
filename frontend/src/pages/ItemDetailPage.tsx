import { Fragment, useEffect, useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { ArrowLeft, AlertTriangle, Pencil, Camera } from 'lucide-react';
import { apiClient } from '../api/apiClient';
import { AppLayout } from '../components/layout/AppLayout';
import { CategoryBadge } from '../components/ui/Badge';
import { Spinner } from '../components/ui/Spinner';
import { PhotoLightbox } from '../components/ui/PhotoLightbox';
import { mediaUrl } from '../utils/mediaUrl';
import { extraFieldRows, parseExtraMetadata } from '../utils/extraMetadata';
import type { Item } from '../types';

export function ItemDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [item, setItem] = useState<Item | null>(null);
  const [loading, setLoading] = useState(true);
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    apiClient.getItem(Number(id))
      .then((r) => setItem(r.data))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <AppLayout><div className="flex justify-center py-24"><Spinner size="lg" /></div></AppLayout>;
  if (!item) return <AppLayout><div className="p-6 text-gray-500">Item not found.</div></AppLayout>;

  // genres gets its own slot in the main details below (swapped in for Source) — excluded here
  // so it doesn't also show up a second time in Extra details.
  const extraRows = extraFieldRows(item.metadata).filter((r) => r.key !== 'genres');
  const genres = parseExtraMetadata(item.metadata)?.genres;

  // coverUrl (the chosen "default" image — often an external reference cover from the barcode
  // match) shown first, followed by the item's own gallery photos, skipping a duplicate if the
  // cover happens to already be one of them.
  const coverMediaUrl = mediaUrl(item.coverUrl);
  const galleryPhotos = [
    ...(coverMediaUrl ? [{ id: 'cover', src: coverMediaUrl, label: 'Cover' }] : []),
    ...(item.photos ?? [])
      .filter((p) => mediaUrl(p.url) !== coverMediaUrl)
      .map((p) => ({ id: p.id, src: mediaUrl(p.url)! })),
  ];

  const handleDelete = async () => {
    if (!confirm('Delete this item from the catalogue?')) return;
    await apiClient.deleteItem(item.id);
    navigate('/items');
  };

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-3xl mx-auto">
        <div className="flex items-center gap-3 mb-6">
          <Link to="/items" className="text-gray-400 hover:text-gray-600"><ArrowLeft size={18} /></Link>
          <h1 className="text-xl font-bold text-gray-900 flex-1 truncate">{item.title}</h1>
          <Link to={`/items/${item.id}/photos`}
            className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 px-3 py-1.5 rounded-lg hover:bg-gray-50">
            <Camera size={14} /> Add photos
          </Link>
          <Link to={`/items/${item.id}/edit`}
            className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 px-3 py-1.5 rounded-lg hover:bg-gray-50">
            <Pencil size={14} /> Edit
          </Link>
        </div>

        <div className="bg-white rounded-xl border border-gray-200 p-5">
          <div className="flex flex-col sm:flex-row gap-5">
            <div className="sm:w-48 shrink-0">
              {galleryPhotos.length > 0 ? (
                <>
                  <button
                    type="button"
                    onClick={() => setLightboxIndex(0)}
                    className="block w-full"
                    title="View photo"
                  >
                    <img
                      src={galleryPhotos[0].src}
                      alt={item.title}
                      className="w-full h-auto aspect-2/3 object-cover rounded-xl bg-gray-100 border border-gray-200 cursor-zoom-in"
                    />
                  </button>
                  {galleryPhotos.length > 1 && (
                    <div className="flex gap-1.5 mt-2 overflow-x-auto pb-1">
                      {galleryPhotos.map((p, i) => (
                        <button
                          type="button"
                          key={p.id}
                          onClick={() => setLightboxIndex(i)}
                          className="shrink-0 rounded-md overflow-hidden border border-gray-200"
                        >
                          <img src={p.src} alt="" className="w-10 h-14 object-cover" />
                        </button>
                      ))}
                    </div>
                  )}
                </>
              ) : (
                <div className="w-full aspect-2/3 rounded-xl bg-gray-100 border border-gray-200" />
              )}
            </div>

            <div className="flex-1 min-w-0 space-y-3">
              <div className="flex items-center gap-2">
                <CategoryBadge category={item.category} />
                <span className="text-sm text-gray-600">{item.format}</span>
              </div>
              {item.subtitle && <p className="text-gray-600">{item.subtitle}</p>}
              {item.description && <p className="text-gray-500 text-sm">{item.description}</p>}

              <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm mt-4 border-t border-gray-100 pt-4">
                {item.publisher && <><dt className="text-gray-400">Publisher / Label</dt><dd className="text-gray-900">{item.publisher}</dd></>}
                {item.releaseYear && <><dt className="text-gray-400">Year</dt><dd className="text-gray-900">{item.releaseYear}</dd></>}
                {item.barcode && <><dt className="text-gray-400">Barcode ({item.barcodeType})</dt><dd className="font-mono text-gray-900">{item.barcode}</dd></>}
                {genres && genres.length > 0 && <><dt className="text-gray-400">Genre</dt><dd className="text-gray-900">{genres.join(', ')}</dd></>}
              </dl>

              {(extraRows.length > 0 || item.externalSource) && (
                <details className="mt-4 border-t border-gray-100 pt-4" open>
                  <summary className="text-sm font-medium text-gray-700 cursor-pointer">Extra details</summary>
                  <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm mt-3">
                    {/* Provenance, not descriptive data — belongs down here rather than crowding
                        the main details with a fact only relevant when double-checking a match. */}
                    {item.externalSource && <><dt className="text-gray-400">Source</dt><dd className="text-gray-900">{item.externalSource}</dd></>}
                    {extraRows.map(({ key, label, render, value }) => (
                      <Fragment key={key}>
                        <dt className="text-gray-400">{label}</dt>
                        <dd className="text-gray-900">{render(value)}</dd>
                      </Fragment>
                    ))}
                  </dl>
                </details>
              )}

              {item.duplicates && item.duplicates.length > 0 && (
                <div className="border border-amber-200 rounded-lg p-3 bg-amber-50">
                  <div className="flex items-center gap-2 text-amber-700 font-medium text-sm mb-2">
                    <AlertTriangle size={15} /> Other versions in catalogue
                  </div>
                  <ul className="space-y-1">
                    {item.duplicates.map((d) => (
                      <li key={d.id}>
                        <Link to={`/items/${d.id}`} className="text-sm text-amber-800 hover:underline">
                          {d.title} — {d.format}{d.releaseYear ? ` (${d.releaseYear})` : ''}{d.publisher ? `, ${d.publisher}` : ''}
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </div>
        </div>

        <button onClick={handleDelete}
          className="mt-4 text-sm text-red-500 hover:text-red-700 hover:underline">
          Delete from catalogue
        </button>

        {lightboxIndex !== null && (
          <PhotoLightbox
            photos={galleryPhotos}
            index={lightboxIndex}
            onIndexChange={setLightboxIndex}
            onClose={() => setLightboxIndex(null)}
          />
        )}
      </div>
    </AppLayout>
  );
}
