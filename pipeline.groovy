pipeline {
    agent any
    environment {
        AWS_REGION = "ca-central-1"
        AWS_SUBNET = "subnet-05151ae63f9d5eef3"
        AWS_SG = "sg-00c3713629ba82bdd"
        IMAGE_CENTOS7 = "ami-e802818c"
        }
    stages {
        stage('Checkout'){
            steps{
                checkout([$class: 'GitSCM', branches: [[name: '*/master']],
                doGenerateSubmoduleConfigurations: false,
                extensions: [],
                submoduleCfg: [],
                userRemoteConfigs: [[url: 'https://github.com/e-kuznetsov/tf-pipeline.git']]])
                checkout([$class: 'GitSCM', branches: [[name: '*/master']],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'RelativeTargetDirectory', 
                                relativeTargetDir: 'tf-devstack']],
                            submoduleCfg: [], 
                            userRemoteConfigs: [[url: 'https://github.com/tungstenfabric/tf-devstack.git']]])
                sh "tar cvzf tf-devstack.tgz tf-devstack/"
            }
        }
        stage('Spin VM') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                                credentialsId: 'aws-creds',
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                InstanceId=$(aws ec2 run-instances \
                    --region $AWS_REGION \
                    --image-id $IMAGE_CENTOS7 \
                    --count 1 \
                    --instance-type t2.xlarge \
                    --key-name jenkins \
                    --security-group-ids $AWS_SG \
                    --subnet-id $AWS_SUBNET | \
                    jq -r '.Instances[].InstanceId')
                aws ec2 wait instance-running --region $AWS_REGION \
                    --instance-ids $InstanceId
                aws ec2 create-tags \
                    --region $AWS_REGION \
                    --resources $InstanceId \
                    --tags Key=Name,Value=$BUILD_TAG
                aws ec2 modify-instance-attribute --instance-id $InstanceId \
                    --region $AWS_REGION \
                    --block-device-mappings \
                    "[{\\\"DeviceName\\\":\\\"/dev/sda1\\\",\\\"Ebs\\\":{\\\"DeleteOnTermination\\\":true}}]"
                echo $InstanceId > .instanseId
                aws ec2 describe-instances \
                    --region $AWS_REGION \
                    --filters \
                    "Name=instance-state-name,Values=running" \
                    "Name=instance-id,Values=$InstanceId" \
                    --query 'Reservations[*].Instances[*].[PrivateIpAddress]' \
                    --output text > .instanceIp
                '''
                }
            }
        }
        stage('Configure VM'){
            steps{
                sh "cat .instanceIp > ansible-devstack/hosts.ini"
                ansiblePlaybook credentialsId: 'centos-jenkins',
                                disableHostKeyChecking: true,
                                extras: '--ssh-common-args=\"-o ConnectionAttempts=100\"',
                                inventory: 'ansible-devstack/hosts.ini',
                                playbook: 'ansible-devstack/devstack-node.yml'
            }
        }
        stage('Deploy tf-defstack'){
            steps{
                sshagent(credentials : ['centos-jenkins']) {
                    sh '''
                    scp ./tf-devstack.tgz centos@$(cat .instanceIp):./
                    ssh -o StrictHostKeyChecking=no centos@$(cat .instanceIp) tar -xzf tf-devstack.tgz
                    ssh -o StrictHostKeyChecking=no centos@$(cat .instanceIp) ./tf-devstack/k8s_manifests/startup.sh
                    '''
                }
            }
        }
    }
    post {
        cleanup {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: 'aws-creds',
                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            sh '''
            aws ec2 terminate-instances --region $AWS_REGION --instance-ids $(cat .instanseId)
            '''
            deleteDir()
            }
        }
    }
}
