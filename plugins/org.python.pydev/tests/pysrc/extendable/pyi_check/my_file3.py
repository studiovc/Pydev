class A(object):

    def charlie(self):
        pass

class MyMessage(object):
    msg: A
    def __init__(self, msg):
        self.msg = msg
