import path from 'node:path';

const root = '/data/data/com.termux/files/home/l2d-static';
const l2dRoot = '/data/data/com.termux/files/home/l2d-main';

// 模型是运行时扫描 dist/models 发现的，构建时不再复制任何模型文件
export default {
  root,
  base: './',
  publicDir: false,
  // l2d 源码里的 tsconfig 引用了未安装的 @hacxy/tsconfig，这里指定本地替身
  tsconfig: path.join(l2dRoot, 'tsconfig.local.json'),
  define: { __VERSION__: JSON.stringify('2.1.1') },
  resolve: {
    alias: {
      l2d: path.join(l2dRoot, 'src/index.ts'),
      '@framework': path.join(l2dRoot, 'src/vendor/cubism6/Framework/src'),
    },
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    emptyOutDir: true,
    assetsInlineLimit: 0,
  },
};
