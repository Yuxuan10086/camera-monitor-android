// 双机监看自拍 - 日志接收云函数
// 接收 APP POST 上传的 logcat 日志，写入 app_logs 集合，供开发者通过 MCP 读取分析
const cloud = require('@cloudbase/node-sdk');

exports.main = async (event) => {
  try {
    const app = cloud.init({ env: cloud.SYMBOL_CURRENT_ENV });
    const db = app.database();

    let body = event.body || '';
    if (event.isBase64Encoded) {
      body = Buffer.from(body, 'base64').toString('utf8');
    }
    const qs = event.queryStringParameters || event.queryString || {};

    await db.collection('app_logs').add({
      role: qs.role || 'unknown',
      device: qs.device || 'unknown',
      appVersion: qs.appVersion || 'unknown',
      content: body.slice(0, 200000),
      createTime: Date.now()
    });

    return {
      statusCode: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ok: true })
    };
  } catch (e) {
    return {
      statusCode: 500,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ok: false, error: String(e) })
    };
  }
};
