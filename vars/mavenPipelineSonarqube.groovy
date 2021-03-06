def call(cfgLocation) {

  def tools = new devops.tools()
  def cfg =''
  def harborHost = '172.31.2.37:180'
  def token = env.JOB_NAME.replaceAll('/' + env.BRANCH_NAME, "")
  pipeline {
    agent {
      kubernetes {
        label 'jenkins-slave'
        yaml """
          apiVersion: v1
          kind: Pod
          metadata:
            name: jenkins-slave
          spec:
            containers:
            - name: jnlp
              image: 172.31.2.37:180/kube-ops/jenkins-slave:v5
              imagePullPolicy: Always
              env: 
              - name: LANG
                value: "en_US.UTF-8"
              resources:
                requests:
                  cpu: "100m"
                  memory: "512Mi"
              volumeMounts:
                - mountPath: /var/run/docker.sock
                  mountPropagation: None
                  name: docker-sock
                  readOnly: false
                - mountPath: /root/.m2/repository
                  mountPropagation: None
                  name: maven-cache
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
                path: /data/jenkins_data/maven_cache
                type: Directory
              name: maven-cache
          """
      }
    }
    options {
      buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '30')
      disableConcurrentBuilds()
      disableResume()
    }
    triggers {
      GenericTrigger(
      genericVariables: [
              // 提取分支名称, 格式为 refs/heads/{branch}
              [key: 'WEBHOOK_REF', value: '$.ref'],
              // 提取用户显示名称
              [key: 'WEBHOOK_USER_NAME', value: '$.user_name'],
              // 提取最近提交 id
              [key: 'WEBHOOK_RECENT_COMMIT_ID', value: '$.commits[-1].id'],
              // 提取最近提交 message
              [key: 'WEBHOOK_RECENT_COMMIT_MESSAGE', value: '$.commits[-1].message'],
              // 如需更多参数可通过查看 request body 参数文档进行提取
      ],
      
      causeString: 'Triggered on $WEBHOOK_REF',
      
      token: token,
      
      printContributedVariables: true,
      printPostContent: true,
      
      silentResponse: false,
  
      // 可选的正则表达式过滤, 比如希望仅在 master 分支上触发, 你可以进行如下配置
      regexpFilterText: '$WEBHOOK_REF',
      regexpFilterExpression: 'refs/heads/' + BRANCH_NAME
      )
    }
    stages {
      stage('变量初始化') {
        steps {
          container('jnlp') {
            script{
              def hookUrl = env.JENKINS_URL + "generic-webhook-trigger/invoke?token=" + token  
              echo "webhook url:" + hookUrl.replaceAll("//","//admin:11aad1e815fe8246b04bb4637909d8c491@")

              sh 'printenv'
              def configFileName = "pipelineCfg.yaml"
              if(cfgLocation && cfgLocation != ""){
                  tools.PrintMes("pipelineCfg位置：" + cfgLocation, "green1")
                  configFileName = cfgLocation
              }
              tools.PrintMes("变量初始化",'green')
              cfg = tools.pipelineCfg(configFileName)
              tools.PrintMes("获取的配置文件信息：" + cfg, "green1")
            }
          }
        }
      }
      stage('开始编译') {
        steps {
          container('jnlp') {
            script{
              tools.PrintMes("开始编译",'green')
            }

            script {
                if(cfg.maven.type && cfg.maven.type == "cmd"){
                    cfg.maven.cmds.each{
                        item ->
                        container('jnlp') {
                            tools.PrintMes("maven 打包命令：" + item,'green1')
                            sh item
                        }
                    }
                }else{
                    cfg.maven.rootFiles.each{
                        item ->
                        container('jnlp') {
                            tools.PrintMes('开始编译pom:' + item, 'green1')
                            def buildString = "mvn -f " + item + " clean package -Dmaven.test.skip=true"
                            tools.PrintMes("maven 打包命令：" + buildString,'green1')
                            sh buildString
                        }
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
            withSonarQubeEnv('Sonarqube-server') {
              sh '''
                export LANG="en_US.utf8"
                Job_name=$(echo ${JOB_NAME}|awk -F '/' '{print $1"-"$2}')
                pro_ject=$(find . -name target -type d|awk -F 'target' '{print $1}')
                binar=$(for i in ${pro_ject}; do echo "-Dsonar.java.binaries=${i}target"; done)
                exclu=$(for i in ${pro_ject}; do echo "-Dsonar.exclusions=${i}src/test/**"; done)
                /usr/local/sonar-scanner/bin/sonar-scanner -X -Dsonar.projectKey=${Job_name} -Dsonar.projectName=${Job_name} -Dsonar.sourceEncoding=UTF-8 -Dsonar.projectVersion=${BUILD_ID} -Dsonar.sources=. ${binar} ${exclu}  
         '''
          }

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
                // println("always")
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
