import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { App } from './App';

test('renders the empty portal screen', () => {
  render(<App />);
  expect(screen.getByRole('heading', { level: 1, name: 'Popdesk Portal' })).toBeInTheDocument();
});
