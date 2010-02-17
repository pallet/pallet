#!/bin/bash
apt-get update
apt-get upgrade -y
apt-get install -y ruby ruby1.8-dev libopenssl-ruby1.8 rdoc build-essential wget rsync libshadow-ruby1.8

cat > ~/.gemrc <<EOF
  gem: --no-rdoc --no-ri
EOF

cd /tmp
wget http://rubyforge.org/frs/download.php/60718/rubygems-1.3.5.tgz
tar zxf rubygems-1.3.5.tgz
cd rubygems-1.3.5
ruby setup.rb
ln -sfv /usr/bin/gem1.8 /usr/bin/gem

gem sources -a http://gems.opscode.com
gem sources -a http://gemcutter.org
gem sources -a http://gems.github.com
gem update --system
gem install chef
gem install passenger # cheat!

cat > ~/solo.rb <<EOF
log_level          :info
log_location       STDOUT
file_cache_path    "/srv/chef"
cookbook_path      [ "/srv/chef/site-cookbooks", "/srv/chef/cookbooks" ]
role_path          "/srv/chef/roles"
ssl_verify_mode    :verify_none
Chef::Log::Formatter.show_time = false
EOF
