def call() {

  def tools = new devops.tools()
  def cfg =''
  def harborHost = '172.31.2.37:180'
  def projectPath = ""
  pipeline {
    agent {
      kubernetes {
        label 'jenkins-slave-nodejs'
        yaml """
          apiVersion: v1
          kind: Pod
          metadata:
            name: jenkins-slave-nodejs
          spec:
            containers:
            - name: jnlp
              image: 172.31.2.37:180/kube-ops/jenkins-slave:v3-with-node
              env: 
              - name: LANG
                value: "en_US.UTF-8"
              resources:
                requests:
                  cpu: "256m"
                  memory: "512Mi"
              volumeMounts:
                - mountPath: /var/run/docker.sock
                  mountPropagation: None
                  name: docker-sock
                  readOnly: false
                - mountPath: /root/cache/front
                  mountPropagation: None
                  name: front-cache
                  readOnly: false
            dnsConfig:
              nameservers:
                - 223.5.5.5
                - 114.114.114.114
            imagePullSecrets:
            - name: harbor-admin  
            tolerations:
            - effect: ''
              key: owner
              operator: Equal
              value: kube-ops
            volumes:
            - hostPath:
                path: /var/run/docker.sock
                type: File
              name: docker-sock
            - hostPath:
                path: /data/jenkins_data/front_cache
                type: Directory
              name: front-cache
          """
      }
    }
    options {
      buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '30')
      disableConcurrentBuilds()
      disableResume()
    }
    stages {
      stage('变量初始化') {
        steps {
          container('jnlp') {
            script{
              tools.PrintMes("变量初始化",'green')
              cfg = tools.pipelineCfg()
              tools.PrintMes("获取的配置文件信息：" + cfg, "green1")
              projectPath = sh(script: "pwd", returnStdout: true)
            }
          }
        }
      }
      stage('开始编译') {
        steps {
          container('jnlp') {
            script{
              tools.PrintMes("开始编译",'green')
              sh 'printenv'
            }

            script {
              cfg.project.rootDir.each{
                item ->
                  container('jnlp') {
                    tools.PrintMes('开始编译:' + item, 'green1')
                    if(cfg.project && cfg.project.npmRegistry){
                      sh "npm config set registry " + cfg.project.npmRegistry
                    }

                    sh "cd ${projectPath}"
                    sh "cd ${item}"
                    sh '''
                      PACKAGE_HASH=$(md5sum package.json | awk '{print $1}');
                      NPM_CACHE=/root/cache/front/${PACKAGE_HASH}.tar;
                      if [ -f $NPM_CACHE ];
                      then
                        echo "Use Cache";
                        tar xf $NPM_CACHE;
                      else
                        npm install ${item};
                        tar cf - ./node_modules > $NPM_CACHE;
                      fi
                    '''
                    sh "npm run build "
                    sh "cd ${projectPath}"
                  }
              }
            }
          }
        }
      }

      stage('静态代码扫描') {
        steps {
          container('jnlp') {
            script {tools.PrintMes('静态代码扫描','blue')}
          }
        }
      }

      stage('类库依赖扫描') {
        steps {
          container('jnlp') {
            script {tools.PrintMes('类库依赖扫描','blue')}
          }
        }
      }

      stage('打包docker镜像') {
        steps {
          container('jnlp') {
            script {tools.PrintMes('开始打包镜像','blue')}
            sh " docker login -u admin  -p Harbor12345 172.31.2.37:180"
          }
          script {
            cfg.docker.files.each{
              item ->
                container('jnlp') {
                  tools.PrintMes('开始打包镜像' + item.imageName, 'green1')
                  tools.PrintMes('dockerfile 位置' + item.dir, 'green1')
                  def buildString = tools.getDockerCMDString(item, harborHost, cfg)
                  tools.PrintMes("docker 打包命令：" + buildString,'green1')
                  sh buildString
                }
            }
          }
        }
      }

      stage('推送镜像Harbor仓库') {
        steps {
          container('jnlp') {
            script {
              tools.PrintMes('推送镜像','blue')
              sh " docker login -u admin  -p Harbor12345 172.31.2.37:180"
              cfg.docker.files.each{
                item ->
                  container('jnlp') {
                    tools.PrintMes('开始推送镜像' + item.imageName, 'green1')
                    def dockerPushString = tools.getDockerPushString(item, harborHost, cfg)
                    tools.PrintMes("harbor 推送命令：" + dockerPushString, 'green1')
                    sh dockerPushString
                  }
              }
            }
          }
        }
      }
    }

    //构建后操作
    post {
        always {
            script{
                tools.PrintMes("结束构建",'blue')
                if(cfg.dingtalk && cfg.dingtalk.robotId){
                  dingtalk (
                      robot: cfg.dingtalk.robotId,
                      title: 'CI构建完成',
                      text: [tools.getDingTalkMsg()],
                      type: 'MARKDOWN',
                      hideAvatar: false
                  )
                }
            }
        }

        success {
            script{
                // currentBuild.description = "\n 构建成功!" 
                tools.PrintMes("构建成功!",'green')
            }
        }

        failure {
            script{
                // currentBuild.description = "\n 构建失败!" 
                tools.PrintMes("构建失败!",'red')
            }
        }

        aborted {
            script{
                // currentBuild.description = "\n 构建取消!" 
                tools.PrintMes("构建取消!",'red')
            }
        }
    }
  }
}
