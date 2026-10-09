import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import './console.css';
import { App } from './App';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Missing #root element');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
