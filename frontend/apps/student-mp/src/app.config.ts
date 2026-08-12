export default defineAppConfig({
  pages: [
    'pages/index/index',
    'pages/course/index',
    'pages/detail/index',
    'pages/profile/index',
    'pages/login/index',
  ],
  subPackages: [
    {
      root: 'subpackages/live',
      pages: ['pages/list/index', 'pages/room/index'],
    },
    {
      root: 'subpackages/coding',
      pages: ['pages/editor/index', 'pages/result/index'],
    },
    {
      root: 'subpackages/quiz',
      pages: ['pages/exam/index', 'pages/result/index'],
    },
    {
      root: 'subpackages/assignment',
      pages: ['pages/detail/index', 'pages/submit/index'],
    },
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#1890ff',
    navigationBarTitleText: 'EP学习',
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
  // C-11: Mini-program privacy protocol — declare all privacy interfaces
  permission: {
    'scope.userLocation': {
      desc: '用于查找附近的线下学习中心',
    },
    'scope.userInfo': {
      desc: '用于完善个人资料',
    },
    'scope.camera': {
      desc: '用于直播课堂观看和拍照上传',
    },
    'scope.record': {
      desc: '用于语音作业录制',
    },
    'scope.writePhotosAlbum': {
      desc: '用于保存学习资料到相册',
    },
  },
  requiredPrivateInfos: [
    'getLocation',
    'chooseLocation',
    'chooseAddress',
  ],
  __usePrivacyCheck__: true,
});
