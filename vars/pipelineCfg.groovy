def call(configLocation) {
  Map pipelineCfg = readYaml file: configLocation
  return pipelineCfg
}
