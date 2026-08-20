import clsx from 'clsx';
import type { MediaCategory } from '../../types';

const VARIANT_CLASSES: Record<string, string> = {
  gray:   'bg-gray-100 text-gray-600',
  indigo: 'bg-indigo-100 text-indigo-700',
  green:  'bg-green-100 text-green-700',
  amber:  'bg-amber-100 text-amber-700',
  red:    'bg-red-100 text-red-700',
};

export function Badge({
  variant = 'gray',
  className,
  children,
}: {
  variant?: keyof typeof VARIANT_CLASSES;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <span className={clsx('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', VARIANT_CLASSES[variant], className)}>
      {children}
    </span>
  );
}

const CATEGORY_COLORS: Record<string, string> = {
  PRINT: 'bg-amber-100 text-amber-800',
  AUDIO: 'bg-purple-100 text-purple-800',
  VIDEO: 'bg-blue-100 text-blue-800',
  GAME: 'bg-green-100 text-green-800',
  OTHER: 'bg-gray-100 text-gray-700',
};

export function CategoryBadge({ category }: { category: MediaCategory }) {
  return (
    <span className={clsx('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', CATEGORY_COLORS[category])}>
      {category}
    </span>
  );
}

export function ConditionBadge({ condition }: { condition: string }) {
  const colors: Record<string, string> = {
    MINT: 'bg-emerald-100 text-emerald-800',
    NEAR_MINT: 'bg-green-100 text-green-800',
    VERY_GOOD: 'bg-teal-100 text-teal-800',
    GOOD: 'bg-yellow-100 text-yellow-800',
    FAIR: 'bg-orange-100 text-orange-800',
    POOR: 'bg-red-100 text-red-800',
    UNKNOWN: 'bg-gray-100 text-gray-600',
  };
  return (
    <span className={clsx('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', colors[condition] ?? colors.UNKNOWN)}>
      {condition.replace('_', ' ')}
    </span>
  );
}
