import type { Decorator, Preview } from '@storybook/react-vite';
import '../tokens.css';

// Theme and density are switched on <html>, the same way the apps do it
// (tokens.md 3·6절). C-9 adds the look; this only places the switches.
const withThemeAndDensity: Decorator = (Story, context) => {
  const root = document.documentElement;
  root.dataset['theme'] = context.globals['theme'] === 'dark' ? 'dark' : 'light';
  root.dataset['density'] = context.globals['density'] === 'console' ? 'console' : 'portal';
  return <Story />;
};

const preview: Preview = {
  globalTypes: {
    theme: {
      description: '테마',
      toolbar: {
        title: '테마',
        icon: 'mirror',
        items: [
          { value: 'light', title: '밝게' },
          { value: 'dark', title: '어둡게' },
        ],
        dynamicTitle: true,
      },
    },
    density: {
      description: '밀도',
      toolbar: {
        title: '밀도',
        icon: 'component',
        items: [
          { value: 'portal', title: '포털' },
          { value: 'console', title: '콘솔' },
        ],
        dynamicTitle: true,
      },
    },
  },
  initialGlobals: { theme: 'light', density: 'portal' },
  decorators: [withThemeAndDensity],
};

export default preview;
