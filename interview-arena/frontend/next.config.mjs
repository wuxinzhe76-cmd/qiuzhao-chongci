/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone', // Docker 部署用,生成最小化生产镜像
  // 后端 API 代理,解决跨域
  // 本地开发：走服务器 Nginx（80端口已通），Nginx 代理到后端容器
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: 'http://117.72.62.12/api/:path*',
      },
    ];
  },
};

export default nextConfig;
