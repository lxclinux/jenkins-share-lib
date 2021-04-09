package devops

//格式化输出
def PrintMes(value,color){
    colors = ['red'   : "\033[40;31m >>>>>>>>>>>${value}<<<<<<<<<<< \033[0m",
              'blue'  : "\033[47;34m ${value} \033[0m",
              'green' : "[1;32m>>>>>>>>>>${value}>>>>>>>>>>[m",
              'green1' : "\033[40;32m >>>>>>>>>>>${value}<<<<<<<<<<< \033[0m" ]
    ansiColor('xterm') {
        println(colors[color])
    }
}

def pipelineCfg(){
  Map pipelineCfg = readYaml file: "pipelineCfg.yaml"
  return pipelineCfg
}

// 获取tag名字
def getImageTagName(){
  return ":"+ env.BRANCH_NAME
}

// 获取docker 编译命令
def getDockerCMDString(item, harborHost, cfg){
  def dockerFile = item.dir + '/' + item.name

  def imageName = harborHost + "/" + cfg.harbor.registryName + "/" + item.imageName + getImageTagName()
  def workspace = item.dir
  def dockerBuildString = "docker build -f %s -t %s %s"
  return sprintf(dockerBuildString, dockerFile, imageName , workspace)
}

// 获取本次编译的内容
def getChangeString() {
    MAX_MSG_LEN = 100
    def changeString = ""
    def changeLogSets = currentBuild.changeSets
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            truncated_msg = entry.msg.take(MAX_MSG_LEN)
            changeString += " - ${truncated_msg} [${entry.author}]\n"
        }
    }
    if (!changeString) {
        changeString = " - No new changes"
    }
    return changeString
}

// 获取推送镜像命令
def getDockerPushString(item, harborHost, cfg){
   def imageName = harborHost + "/" + cfg.harbor.registryName + "/" + item.imageName + getImageTagName()
   def dockerPushString = "docker push %s"
   return sprintf(dockerPushString, imageName)
}

// 获取删除镜像命令
def getDockerRemoveString(item, harborHost, cfg){
   def imageName = harborHost + "/" + cfg.harbor.registryName + "/" + item.imageName + getImageTagName()
   def dockerRemoveString = "docker rmi %s"
   return sprintf(dockerRemoveString, imageName)
}

def getDingTalkMsg2(){
   def DELIMITER = "\n";
   println "打印本次编译变量"  
   println currentBuild.projectName
   println currentBuild.absoluteUrl
   println env.JOB_NAME
   println env.JOB_URL
   println currentBuild.result
   println currentBuild.durationString
   println currentBuild.durationString
   println env.BUILD_USER
}

def getDingTalkMsg(){
        def cause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
        def userName = "--"
        if(cause){
            userName = cause.getUserName()?:"--"
        }
            def DELIMITER = "\n";
     return sprintf("# [%s](%s)", currentBuild.fullDisplayName, currentBuild.absoluteUrl) + DELIMITER +
            "---" + DELIMITER +
            sprintf("- 任务：[%s](%s)", env.JOB_NAME, env.JOB_URL) + DELIMITER +
            sprintf("- 状态：%s", currentBuild.currentResult)  + DELIMITER +
            sprintf("- 持续时间：%s", currentBuild.durationString)  + DELIMITER +
            sprintf("- 变更集：%s", getChangeString()?:"--")  + DELIMITER +
            sprintf("- 执行人：%s", userName) + DELIMITER 
}
