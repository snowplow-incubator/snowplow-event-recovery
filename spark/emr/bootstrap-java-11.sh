#!/bin/bash
set -e

cat <<_EOF_> /home/hadoop/secondstage.sh
#!/bin/bash
while true; do
NODEPROVISIONSTATE=\`sed -n '/localInstance [{]/,/[}]/{
/nodeProvisionCheckinRecord [{]/,/[}]/ {
/status: / { p }
/[}]/a
}
/[}]/a
}' /emr/instance-controller/lib/info/job-flow-state.txt | awk ' { print \$2 }'\`
if [ "\$NODEPROVISIONSTATE" == "SUCCESSFUL" ];
then
  sleep 10
  echo "Running my post provision bootstrap post Hadoop software install"
  sudo yum install java-11-amazon-corretto
  update-alternatives --set java /usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java
  exit;
fi
sleep 10;
done
_EOF_
sudo bash /home/hadoop/secondstage.sh > /home/hadoop/secondstage.sh.log 2>&1  &
exit 0
