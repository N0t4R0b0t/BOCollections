import { useCallback, useEffect, useRef } from 'react';
import type { ThriftItem } from '../../types/thrift';
import { OWNED_STATUS_COLOR } from '../../types/thrift';

interface Props {
  imageDataUrl: string;
  items: ThriftItem[];
  selectedIndex: number | null;
  onSelect: (index: number) => void;
}

export function ThriftResultOverlay({ imageDataUrl, items, selectedIndex, onSelect }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const imgRef = useRef<HTMLImageElement | null>(null);

  const draw = useCallback((ctx: CanvasRenderingContext2D, img: HTMLImageElement) => {
    const { width: W, height: H } = ctx.canvas;
    ctx.clearRect(0, 0, W, H);
    ctx.drawImage(img, 0, 0, W, H);

    items.forEach((item, i) => {
      const { x, y, w, h } = item.bbox;
      const color = OWNED_STATUS_COLOR[item.ownedStatus];
      const isSelected = i === selectedIndex;

      // An arrow callout rather than a filled box — the whole point is to point AT the item
      // without covering it up (a filled rect over a thin DVD spine hid the exact thing being
      // located). Anchor the tail above the item when there's room, else below, so the arrow
      // and its label chip both land in open space around the item instead of on top of it.
      const centerX = (x + w / 2) * W;
      const roomAbove = y > 0.22;
      const tailY = roomAbove ? (y - 0.12) * H : (y + h + 0.12) * H;
      const tipY = roomAbove ? y * H - 6 : (y + h) * H + 6;

      ctx.globalAlpha = 1;
      ctx.strokeStyle = color;
      ctx.fillStyle = color;
      ctx.lineWidth = isSelected ? 4 : 3;

      ctx.beginPath();
      ctx.moveTo(centerX, tailY);
      ctx.lineTo(centerX, tipY);
      ctx.stroke();

      const angle = Math.atan2(tipY - tailY, 0);
      const headLen = isSelected ? 14 : 11;
      ctx.beginPath();
      ctx.moveTo(centerX, tipY);
      ctx.lineTo(centerX - headLen * Math.cos(angle - Math.PI / 6), tipY - headLen * Math.sin(angle - Math.PI / 6));
      ctx.lineTo(centerX - headLen * Math.cos(angle + Math.PI / 6), tipY - headLen * Math.sin(angle + Math.PI / 6));
      ctx.closePath();
      ctx.fill();

      // Label chip at the tail end, clamped so it never runs off either edge of the canvas.
      const label = item.title.length > 22 ? item.title.slice(0, 20) + '…' : item.title;
      const fontSize = 14;
      ctx.font = `bold ${fontSize}px sans-serif`;
      const textW = ctx.measureText(label).width;
      const chipH = fontSize + 10;
      const chipX = Math.min(Math.max(centerX - textW / 2 - 6, 4), W - textW - 16);
      const chipY = roomAbove ? tailY - chipH - 4 : tailY + 4;

      ctx.globalAlpha = 0.9;
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.roundRect(chipX, chipY, textW + 12, chipH, 6);
      ctx.fill();

      ctx.globalAlpha = 1;
      ctx.fillStyle = '#fff';
      ctx.fillText(label, chipX + 6, chipY + chipH - 7);
    });
  }, [items, selectedIndex]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const img = new Image();
    imgRef.current = img;
    img.onload = () => {
      canvas.width = img.naturalWidth;
      canvas.height = img.naturalHeight;
      draw(ctx, img);
    };
    img.src = imageDataUrl;
  }, [imageDataUrl, draw]);

  function handleClick(e: React.MouseEvent<HTMLCanvasElement>) {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    const cx = (e.clientX - rect.left) * scaleX;
    const cy = (e.clientY - rect.top) * scaleY;
    const W = canvas.width;
    const H = canvas.height;

    // Hit-test in reverse order so top-most (last drawn) wins
    for (let i = items.length - 1; i >= 0; i--) {
      const { x, y, w, h } = items[i].bbox;
      if (cx >= x * W && cx <= (x + w) * W && cy >= y * H && cy <= (y + h) * H) {
        onSelect(i);
        return;
      }
    }
    onSelect(-1);
  }

  return (
    <canvas
      ref={canvasRef}
      onClick={handleClick}
      className="w-full h-full object-contain cursor-pointer"
      style={{ display: 'block' }}
    />
  );
}
