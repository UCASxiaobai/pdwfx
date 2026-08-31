const path = require("path");
const fs = require("fs");

const AGENT_LOG = path.resolve(__dirname, "..", "debug-b9c0b8.log");

module.exports = {
  publicPath: "./",
  outputDir: "dist",
  assetsDir: "static",
  lintOnSave: false,
  productionSourceMap: false,
  transpileDependencies: ["ol", "color-rgba", "color-parse", "color-space"],
  devServer: {
    port: 5173,
    before(app) {
      app.post("/__agent_log", function(req, res) {
        var chunks = [];
        req.on("data", function(c) {
          chunks.push(c);
        });
        req.on("end", function() {
          try {
            var line = Buffer.concat(chunks).toString("utf8");
            if (line) {
              fs.appendFileSync(AGENT_LOG, line.trim() + "\n", "utf8");
            }
          } catch (e) {}
          res.statusCode = 204;
          res.end();
        });
      });
    },
    // stream-api 必须写在 /api 之前；pathRewrite 去掉前缀，否则 stream 收到 /stream-api/... 会 404
    proxy: {
      "/stream-api": {
        target: "http://localhost:19080",
        changeOrigin: true,
        pathRewrite: function (p) {
          return p.replace(/^\/stream-api/, "") || "/";
        }
      },
      "/api": {
        target: "http://localhost:18080",
        changeOrigin: true
      }
    }
  },
  chainWebpack: (config) => {
    config.resolve.alias.set("@", path.resolve(__dirname, "src"));
  }
};
