export default defineAppConfig({
  pages: [
    'pages/index/index',
    'pages/course/index',
    'pages/course-detail/index',
    'pages/learning/index',
    'pages/profile/index',
    'pages/login/index',
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#1890ff',
    navigationBarTitleText: 'Education Platform',
    navigationBarTextStyle: 'white',
  },
  tabBar: {
    color: '#8c8c8c',
    selectedColor: '#1890ff',
    backgroundColor: '#ffffff',
    borderStyle: 'black',
    list: [
      { pagePath: 'pages/index/index', text: '首页' },
      { pagePath: 'pages/course/index', text: '课程' },
      { pagePath: 'pages/profile/index', text: '我的' },
    ],
  },
});
