export default defineAppConfig({
  pages: [
    'pages/index/index',
    'pages/search/index',
    'pages/chat/index',
    'pages/community/index',
    'pages/profile/index',
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#722ed1',
    navigationBarTitleText: '知识中台',
    navigationBarTextStyle: 'white',
  },
  tabBar: {
    color: '#8c8c8c',
    selectedColor: '#722ed1',
    backgroundColor: '#ffffff',
    borderStyle: 'black',
    list: [
      { pagePath: 'pages/index/index', text: '首页' },
      { pagePath: 'pages/search/index', text: '搜索' },
      { pagePath: 'pages/chat/index', text: '问答' },
      { pagePath: 'pages/community/index', text: '社区' },
      { pagePath: 'pages/profile/index', text: '我的' },
    ],
  },
});
