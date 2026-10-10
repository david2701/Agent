import { memo } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import { Link } from 'react-router'
import remarkGfm from 'remark-gfm'
import { cn } from '@/lib/cn'

const components: Components = {
  a: ({ href, children }) => {
    if (href && href.startsWith('/') && !href.startsWith('/api/')) return <Link to={href}>{children}</Link>
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    )
  },
  img: ({ src, alt }) => <img src={typeof src === 'string' ? src : undefined} alt={alt ?? ''} loading="lazy" />,
  input: ({ checked, type }) => (type === 'checkbox' ? <input type="checkbox" checked={!!checked} readOnly disabled /> : null),
}

export const Markdown = memo(function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={cn('k-prose', className)}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  )
})
